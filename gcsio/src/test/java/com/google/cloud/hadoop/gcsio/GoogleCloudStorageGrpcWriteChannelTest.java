package com.google.cloud.hadoop.gcsio;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.api.client.util.BackOff;
import com.google.auth.Credentials;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageImpl.BackOffFactory;
import com.google.cloud.hadoop.util.AsyncWriteChannelOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.storage.v2.ChecksummedData;
import com.google.storage.v2.Object;
import com.google.storage.v2.ObjectChecksums;
import com.google.storage.v2.QueryWriteStatusRequest;
import com.google.storage.v2.QueryWriteStatusResponse;
import com.google.storage.v2.StartResumableWriteRequest;
import com.google.storage.v2.StartResumableWriteResponse;
import com.google.storage.v2.StorageGrpc;
import com.google.storage.v2.StorageGrpc.StorageImplBase;
import com.google.storage.v2.StorageGrpc.StorageStub;
import com.google.storage.v2.WriteObjectRequest;
import com.google.storage.v2.WriteObjectResponse;
import com.google.storage.v2.WriteObjectSpec;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class GoogleCloudStorageGrpcWriteChannelTest {

  private static final int GCS_MINIMUM_CHUNK_SIZE = 256 * 1024;
  private static final String V1_BUCKET_NAME = "bucket-name";
  private static final String BUCKET_NAME = GrpcChannelUtils.toV2BucketName(V1_BUCKET_NAME);
  private static final String OBJECT_NAME = "object-name";
  private static final String UPLOAD_ID = "upload-id";
  private static final String CONTENT_TYPE = "image/jpeg";
  private static final StartResumableWriteRequest START_REQUEST =
      StartResumableWriteRequest.newBuilder()
          .setWriteObjectSpec(
              WriteObjectSpec.newBuilder()
                  .setResource(
                      Object.newBuilder()
                          .setBucket(BUCKET_NAME)
                          .setName(OBJECT_NAME)
                          .setContentType(CONTENT_TYPE)))
          .build();
  private static final QueryWriteStatusRequest WRITE_STATUS_REQUEST =
      QueryWriteStatusRequest.newBuilder().setUploadId(UPLOAD_ID).build();
  private static final Watchdog watchdog = Watchdog.create(Duration.ofMillis(100));

  private StorageStub stub;
  private FakeService fakeService;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  @Mock private Credentials mockCredentials;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    fakeService = spy(new FakeService());
    String serverName = InProcessServerBuilder.generateName();
    InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(fakeService)
        .build()
        .start();
    stub =
        StorageGrpc.newStub(InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  @Test
  public void writeSendsSingleInsertObjectRequestWithChecksums() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder().setPersistedSize(0).build(),
                QueryWriteStatusResponse.newBuilder().setPersistedSize(0).build())
            .iterator());

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    WriteObjectRequest expectedInsertRequest =
        WriteObjectRequest.newBuilder()
            .setUploadId(UPLOAD_ID)
            .setChecksummedData(
                ChecksummedData.newBuilder().setContent(data).setCrc32C((uInt32Value(863614154))))
            .setObjectChecksums(ObjectChecksums.newBuilder().setCrc32C((uInt32Value(863614154))))
            .setFinishWrite(true)
            .build();

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    verify(fakeService.insertRequestObserver, times(1)).onNext(expectedInsertRequest);
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeSendsSingleInsertObjectRequestWithoutChecksums() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(false).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    WriteObjectRequest expectedInsertRequest =
        WriteObjectRequest.newBuilder()
            .setUploadId(UPLOAD_ID)
            .setChecksummedData(ChecksummedData.newBuilder().setContent(data))
            .setFinishWrite(true)
            .build();

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    verify(fakeService.insertRequestObserver, times(1)).onNext(expectedInsertRequest);
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeSendsMultipleInsertObjectRequests() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(GCS_MINIMUM_CHUNK_SIZE)
                    .build(),
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(2 * GCS_MINIMUM_CHUNK_SIZE)
                    .build())
            .iterator());

    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE * 5 / 2);
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    ArgumentCaptor<WriteObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(WriteObjectRequest.class);

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    verify(fakeService.insertRequestObserver, times(1)).onNext(requestCaptor.capture());
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeSendsMultipleInsertObjectRequestsWithChecksums() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setGrpcChecksumsEnabled(true).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(GCS_MINIMUM_CHUNK_SIZE)
                    .build(),
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(2 * GCS_MINIMUM_CHUNK_SIZE)
                    .build())
            .iterator());

    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE * 5 / 2);
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    ArgumentCaptor<WriteObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(WriteObjectRequest.class);

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    verify(fakeService.insertRequestObserver, times(1)).onNext(requestCaptor.capture());
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeHandlesUncommittedData() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(GCS_MINIMUM_CHUNK_SIZE * 3 / 4)
                    .build())
            .iterator());

    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE * 3 / 2);
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    ArgumentCaptor<WriteObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(WriteObjectRequest.class);

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    // TODO(b/150892988): Use this mock when implement resuming after a transient error.
    // verify(fakeService, times(1)).queryWriteStatus(eq(WRITE_STATUS_REQUEST), any());
    verify(fakeService.insertRequestObserver, times(1)).onNext(requestCaptor.capture());
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeUsesContentGenerationIfProvided() throws Exception {
    AsyncWriteChannelOptions options = AsyncWriteChannelOptions.builder().build();
    ObjectWriteConditions writeConditions =
        ObjectWriteConditions.builder().setContentGenerationMatch(1L).build();
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    StartResumableWriteRequest.Builder expectedRequestBuilder = START_REQUEST.toBuilder();
    expectedRequestBuilder.getWriteObjectSpecBuilder().setIfGenerationMatch(1L);
    verify(fakeService, times(1)).startResumableWrite(eq(expectedRequestBuilder.build()), any());
  }

  @Test
  public void writeUsesMetaGenerationIfProvided() throws Exception {
    AsyncWriteChannelOptions options = AsyncWriteChannelOptions.builder().build();
    ObjectWriteConditions writeConditions =
        ObjectWriteConditions.builder().setMetaGenerationMatch(1L).build();
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    StartResumableWriteRequest.Builder expectedRequestBuilder = START_REQUEST.toBuilder();
    expectedRequestBuilder.getWriteObjectSpecBuilder().setIfMetagenerationMatch(1L);
    verify(fakeService, times(1)).startResumableWrite(eq(expectedRequestBuilder.build()), any());
  }

  @Test
  public void writeUsesRequesterPaysProjectIfProvided() throws Exception {
    AsyncWriteChannelOptions options = AsyncWriteChannelOptions.builder().build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ "project-id");

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();

    StartResumableWriteRequest.Builder expectedRequestBuilder = START_REQUEST.toBuilder();
    expectedRequestBuilder.getCommonRequestParamsBuilder().setUserProject("project-id");
    verify(fakeService, times(1)).startResumableWrite(eq(expectedRequestBuilder.build()), any());
  }

  @Test
  public void writeHandlesErrorOnStartRequest() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    fakeService.setStartRequestException(new IOException("Error!"));
    writeChannel.initialize();
    writeChannel.write(ByteBuffer.wrap("test data".getBytes()));

    assertThrows(IOException.class, writeChannel::close);
  }

  @Test
  public void writeHandlesErrorOnInsertRequest() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setInsertRequestException(new IOException("Error!"));

    writeChannel.initialize();
    writeChannel.write(ByteBuffer.wrap("test data".getBytes()));

    assertThrows(IOException.class, writeChannel::close);
  }

  @Test
  public void writeHandlesErrorOnQueryWriteStatusRequest() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setQueryWriteStatusException(new IOException("Test error!"));
    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE * 2);

    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
  }

  @Test
  public void writeHandlesErrorOnInsertRequestWithUncommittedData() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setInsertRequestException(new IOException("Error!"));
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(GCS_MINIMUM_CHUNK_SIZE * 3 / 4)
                    .build())
            .iterator());

    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE * 3 / 2);
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());

    assertThrows(IOException.class, writeChannel::close);
  }

  @Test
  public void writeHandlesErrorOnInsertRequestWithoutUncommittedData() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();
    fakeService.setInsertRequestException(new IOException("Error!"));
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder()
                    .setPersistedSize(GCS_MINIMUM_CHUNK_SIZE)
                    .build())
            .iterator());

    ByteString data = createTestData(GCS_MINIMUM_CHUNK_SIZE);
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());

    assertThrows(IOException.class, writeChannel::close);
  }

  @Test
  public void writeOneChunkWithSingleErrorAndResume() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setUploadChunkSize(GCS_MINIMUM_CHUNK_SIZE).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(
            options, writeConditions, /* requesterPaysProject= */ null, () -> BackOff.ZERO_BACKOFF);
    fakeService.setInsertObjectExceptions(
        ImmutableList.of(new StatusException(Status.DEADLINE_EXCEEDED)));
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(QueryWriteStatusResponse.newBuilder().setPersistedSize(1).build())
            .iterator());
    ByteString chunk = createTestData(GCS_MINIMUM_CHUNK_SIZE);
    ArgumentCaptor<WriteObjectRequest> requestCaptor =
        ArgumentCaptor.forClass(WriteObjectRequest.class);

    writeChannel.initialize();
    writeChannel.write(chunk.asReadOnlyByteBuffer());
    writeChannel.close();

    verify(fakeService, times(1)).startResumableWrite(eq(START_REQUEST), any());
    verify(fakeService, times(1)).queryWriteStatus(eq(WRITE_STATUS_REQUEST), any());
    verify(fakeService.insertRequestObserver, atLeast(1)).onNext(requestCaptor.capture());
    // TODO(hgong): Figure out a way to check the expected requests and actual reqeusts builder.
    // assertEquals(expectedRequests, requestCaptor.getAllValues());
    verify(fakeService.insertRequestObserver, atLeast(1)).onCompleted();
  }

  @Test
  public void writeOneChunkWithSingleErrorFailedToResume() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setUploadChunkSize(GCS_MINIMUM_CHUNK_SIZE).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);
    fakeService.setInsertObjectExceptions(
        ImmutableList.of(new StatusException(Status.DEADLINE_EXCEEDED)));
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(QueryWriteStatusResponse.newBuilder().setPersistedSize(-1).build())
            .iterator());
    ByteString chunk = createTestData(GCS_MINIMUM_CHUNK_SIZE);

    writeChannel.initialize();
    writeChannel.write(chunk.asReadOnlyByteBuffer());
    assertThrows(IOException.class, writeChannel::close);
  }

  @Test
  public void retryInsertOnIOException() throws Exception {
    AsyncWriteChannelOptions options =
        AsyncWriteChannelOptions.builder().setUploadChunkSize(GCS_MINIMUM_CHUNK_SIZE).build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;
    GoogleCloudStorageGrpcWriteChannel writeChannel =
        newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);
    fakeService.setInsertObjectExceptions(
        ImmutableList.of(
            new StatusException(Status.DEADLINE_EXCEEDED),
            new StatusException(Status.DEADLINE_EXCEEDED),
            new StatusException(Status.DEADLINE_EXCEEDED),
            new StatusException(Status.DEADLINE_EXCEEDED),
            new StatusException(Status.DEADLINE_EXCEEDED)));
    fakeService.setQueryWriteStatusResponses(
        ImmutableList.of(
                QueryWriteStatusResponse.newBuilder().setPersistedSize(1).build(),
                QueryWriteStatusResponse.newBuilder().setPersistedSize(1).build(),
                QueryWriteStatusResponse.newBuilder().setPersistedSize(1).build(),
                QueryWriteStatusResponse.newBuilder().setPersistedSize(1).build())
            .iterator());
    ByteString chunk = createTestData(GCS_MINIMUM_CHUNK_SIZE);

    writeChannel.initialize();
    writeChannel.write(chunk.asReadOnlyByteBuffer());

    assertThrows(IOException.class, writeChannel::close);

    // TODO: assert number of retires;
  }

  @Test
  public void writeFailsBeforeInitialize() {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    assertThrows(
        IllegalStateException.class,
        () -> writeChannel.write(ByteBuffer.wrap("test data".getBytes())));
  }

  @Test
  public void writeFailsAfterClose() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    writeChannel.initialize();
    writeChannel.close();

    assertThrows(
        ClosedChannelException.class,
        () -> writeChannel.write(ByteBuffer.wrap("test data".getBytes())));
  }

  @Test
  public void closeFailsBeforeInitilize() {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    assertThrows(IllegalStateException.class, writeChannel::close);
  }

  @Test
  public void getItemInfoReturnsCorrectItemInfo() throws Exception {
    byte[] expectedMd5Hash = {
      -109, 66, -75, 122, -93, -111, 86, -26, 54, -45, -55, -64, 0, 58, 115, -21
    };
    byte[] expectedCrc32C = {51, 121, -76, -54};

    fakeService.setObject(
        FakeService.DEFAULT_OBJECT.toBuilder()
            .setSize(9)
            .setGeneration(1)
            .setMetageneration(2)
            .setCreateTime(Timestamp.newBuilder().setSeconds(1560485630).setNanos(7000000))
            .setUpdateTime(Timestamp.newBuilder().setSeconds(1560495630).setNanos(123000000))
            .setContentType(CONTENT_TYPE)
            .setContentEncoding("content-encoding")
            .putMetadata("metadata-key-1", "dGVzdC1tZXRhZGF0YQ==")
            .setChecksums(
                ObjectChecksums.newBuilder()
                    .setMd5Hash(ByteString.copyFrom(expectedMd5Hash))
                    .setCrc32C(uInt32Value(863614154))
                    .build())
            .build());
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());
    writeChannel.close();
    GoogleCloudStorageItemInfo itemInfo = writeChannel.getItemInfo();

    Map<String, byte[]> expectedMetadata =
        ImmutableMap.of(
            "metadata-key-1",
            new byte[] {116, 101, 115, 116, 45, 109, 101, 116, 97, 100, 97, 116, 97});
    GoogleCloudStorageItemInfo expectedItemInfo =
        GoogleCloudStorageItemInfo.createObject(
            new StorageResourceId(V1_BUCKET_NAME, OBJECT_NAME),
            1560485630007L,
            1560495630123L,
            /* size= */ 9,
            CONTENT_TYPE,
            "content-encoding",
            expectedMetadata,
            1,
            2,
            new VerificationAttributes(expectedMd5Hash, expectedCrc32C));

    assertThat(itemInfo).isEqualTo(expectedItemInfo);
  }

  @Test
  public void getItemInfoReturnsNullBeforeClose() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    ByteString data = ByteString.copyFromUtf8("test data");
    writeChannel.initialize();
    writeChannel.write(data.asReadOnlyByteBuffer());

    assertNull(writeChannel.getItemInfo());
  }

  @Test
  public void isOpenReturnsFalseBeforeInitialize() {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    assertFalse(writeChannel.isOpen());
  }

  @Test
  public void isOpenReturnsTrueAfterInitialize() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    writeChannel.initialize();
    assertTrue(writeChannel.isOpen());
  }

  @Test
  public void isOpenReturnsFalseAfterClose() throws Exception {
    GoogleCloudStorageGrpcWriteChannel writeChannel = newWriteChannel();

    writeChannel.initialize();
    writeChannel.close();
    assertFalse(writeChannel.isOpen());
  }

  private GoogleCloudStorageGrpcWriteChannel newWriteChannel(
      AsyncWriteChannelOptions options,
      ObjectWriteConditions writeConditions,
      String requesterPaysProject) {
    return newWriteChannel(
        options, writeConditions, requesterPaysProject, () -> BackOff.STOP_BACKOFF);
  }

  private GoogleCloudStorageGrpcWriteChannel newWriteChannel(
      AsyncWriteChannelOptions options,
      ObjectWriteConditions writeConditions,
      String requesterPaysProject,
      BackOffFactory backOffFactory) {
    return new GoogleCloudStorageGrpcWriteChannel(
        new FakeStubProvider(mockCredentials),
        executor,
        options,
        new StorageResourceId(V1_BUCKET_NAME, OBJECT_NAME),
        CreateObjectOptions.DEFAULT_NO_OVERWRITE.toBuilder().setContentType(CONTENT_TYPE).build(),
        watchdog,
        writeConditions,
        requesterPaysProject,
        backOffFactory);
  }

  private GoogleCloudStorageGrpcWriteChannel newWriteChannel() {
    AsyncWriteChannelOptions options = AsyncWriteChannelOptions.builder().build();
    ObjectWriteConditions writeConditions = ObjectWriteConditions.NONE;

    return newWriteChannel(options, writeConditions, /* requesterPaysProject= */ null);
  }

  /* Returns an int with the same bytes as the uint32 representation of value. */
  private int uInt32Value(long value) {
    ByteBuffer buffer = ByteBuffer.allocate(4);
    buffer.putInt(0, (int) value);
    return buffer.getInt();
  }

  private ByteString createTestData(int numBytes) {
    byte[] result = new byte[numBytes];
    for (int i = 0; i < numBytes; ++i) {
      // Sequential data makes it easier to compare expected vs. actual in
      // case of error. Since chunk sizes are multiples of 256, the modulo
      // ensures chunks have different data.
      result[i] = (byte) (i % 257);
    }

    return ByteString.copyFrom(result);
  }

  private static class FakeGrpcDecorator implements StorageStubProvider.GrpcDecorator {

    @Override
    public ManagedChannelBuilder<?> createChannelBuilder(String target) {
      return null;
    }

    @Override
    public AbstractStub<?> applyCallOption(AbstractStub<?> stub) {
      return stub;
    }
  }

  private class FakeStubProvider extends StorageStubProvider {

    FakeStubProvider(Credentials credentials) {
      super(GoogleCloudStorageOptions.DEFAULT, null, new FakeGrpcDecorator());
    }

    @Override
    public StorageStub newAsyncStub() {
      return stub;
    }
  }

  private static class FakeService extends StorageImplBase {

    static final Object DEFAULT_OBJECT =
        Object.newBuilder()
            .setBucket(BUCKET_NAME)
            .setName(OBJECT_NAME)
            .setGeneration(1)
            .setMetageneration(2)
            .build();

    InsertRequestObserver insertRequestObserver = spy(new InsertRequestObserver());

    private Throwable startRequestException;
    private List<Throwable> insertObjectExceptions;
    private Throwable queryWriteStatusException;
    private Iterator<QueryWriteStatusResponse> queryWriteStatusResponses;

    @Override
    public void startResumableWrite(
        StartResumableWriteRequest request,
        StreamObserver<StartResumableWriteResponse> responseObserver) {
      if (startRequestException != null) {
        responseObserver.onError(startRequestException);
      } else {
        StartResumableWriteResponse response =
            StartResumableWriteResponse.newBuilder().setUploadId(UPLOAD_ID).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    }

    @Override
    public void queryWriteStatus(
        QueryWriteStatusRequest request,
        StreamObserver<QueryWriteStatusResponse> responseObserver) {
      if (queryWriteStatusException != null && queryWriteStatusResponses.hasNext()) {
        responseObserver.onError(queryWriteStatusException);
      } else {
        QueryWriteStatusResponse response = queryWriteStatusResponses.next();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      }
    }

    @Override
    public StreamObserver<WriteObjectRequest> writeObject(
        StreamObserver<WriteObjectResponse> responseObserver) {
      if (insertObjectExceptions != null && insertObjectExceptions.size() > 0) {
        Throwable throwable = insertObjectExceptions.remove(0);
        if (!throwable.getClass().isAssignableFrom(Throwable.class)
            || throwable.getCause() != null) {
          insertRequestObserver.insertRequestException = throwable;
          insertRequestObserver.resumeFromInsertException = true;
        }
      }
      insertRequestObserver.responseObserver = responseObserver;
      return insertRequestObserver;
    }

    public void setObject(Object object) {
      insertRequestObserver.object = object;
      insertRequestObserver.writeObjectResponse =
          WriteObjectResponse.newBuilder().setResource(object).build();
    }

    void setQueryWriteStatusResponses(Iterator<QueryWriteStatusResponse> responses) {
      queryWriteStatusResponses = responses;
    }

    void setQueryWriteStatusException(Throwable t) {
      queryWriteStatusException = t;
    }

    void setStartRequestException(Throwable t) {
      startRequestException = t;
    }

    void setInsertRequestException(Throwable t) {
      insertRequestObserver.insertRequestException = t;
    }

    public void setInsertObjectExceptions(List<Throwable> insertObjectExceptions) {
      // Make a copy so caller can pass in an immutable list (this implementation needs to
      // update the list).
      this.insertObjectExceptions = Lists.newArrayList(insertObjectExceptions);
    }

    private static class InsertRequestObserver implements StreamObserver<WriteObjectRequest> {

      private StreamObserver<WriteObjectResponse> responseObserver;
      private Object object = DEFAULT_OBJECT;
      WriteObjectResponse writeObjectResponse =
          WriteObjectResponse.newBuilder().setResource(object).build();
      Throwable insertRequestException;
      boolean resumeFromInsertException = false;

      @Override
      public void onNext(WriteObjectRequest request) {
        if (insertRequestException != null) {
          responseObserver.onError(insertRequestException);
          if (resumeFromInsertException) {
            insertRequestException = null;
          }
        } else {
          responseObserver.onNext(writeObjectResponse);
        }
      }

      @Override
      public void onError(Throwable t) {
        responseObserver.onError(t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    }
  }
}
