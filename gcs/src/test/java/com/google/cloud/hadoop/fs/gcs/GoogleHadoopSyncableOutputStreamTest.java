/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.fs.gcs;

import static com.google.cloud.hadoop.fs.gcs.GhfsStatistic.INVOCATION_HFLUSH;
import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_SYNC_MIN_INTERVAL_MS;
import static com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystemConfiguration.GCS_OUTPUT_STREAM_TYPE;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.toIntExact;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.hadoop.fs.gcs.GoogleHadoopFileSystem.OutputStreamType;
import com.google.cloud.hadoop.gcsio.CreateFileOptions;
import com.google.common.util.concurrent.Futures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unittests for fine-grained edge cases in GoogleHadoopSyncableOutputStream. */
@RunWith(JUnit4.class)
public class GoogleHadoopSyncableOutputStreamTest {
  @Mock private ExecutorService mockExecutorService;

  private GoogleHadoopFileSystem ghfs;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);

    ghfs = GoogleHadoopFileSystemTestHelper.createInMemoryGoogleHadoopFileSystem();
    ghfs.getConf().setEnum(GCS_OUTPUT_STREAM_TYPE.getKey(), OutputStreamType.SYNCABLE_COMPOSITE);
  }

  @After
  public void tearDown() throws IOException {
    ghfs.close();

    verifyNoMoreInteractions(mockExecutorService);
  }

  @Test
  public void testEndToEndHsync() throws Exception {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object.txt"));
    FSDataOutputStream fout = ghfs.create(objectPath);

    byte[] data1 = {0x0f, 0x0e, 0x0e, 0x0d};
    byte[] data2 = {0x0b, 0x0e, 0x0e, 0x0f};
    byte[] data3 = {0x04, 0x02};
    byte[] data1Read = new byte[4];
    byte[] data2Read = new byte[4];
    byte[] data3Read = new byte[2];

    fout.write(data1, 0, data1.length);
    fout.hsync();

    assertThat(ghfs.getFileStatus(objectPath).getLen()).isEqualTo(4);
    FSDataInputStream fin = ghfs.open(objectPath);
    fin.read(data1Read);
    fin.close();
    assertThat(data1Read).isEqualTo(data1);

    fout.write(data2, 0, data2.length);
    fout.hsync();

    assertThat(ghfs.getFileStatus(objectPath).getLen()).isEqualTo(8);
    fin = ghfs.open(objectPath);
    fin.read(data1Read);
    fin.read(data2Read);
    fin.close();
    assertThat(data1Read).isEqualTo(data1);
    assertThat(data2Read).isEqualTo(data2);

    fout.write(data3, 0, data3.length);
    fout.close();

    assertThat(ghfs.getFileStatus(objectPath).getLen()).isEqualTo(10);
    fin = ghfs.open(objectPath);
    fin.read(data1Read);
    fin.read(data2Read);
    fin.read(data3Read);
    fin.close();
    assertThat(data1Read).isEqualTo(data1);
    assertThat(data2Read).isEqualTo(data2);
    assertThat(data3Read).isEqualTo(data3);
  }

  @Test
  public void testExceptionOnDelete() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object2.txt"));
    GoogleHadoopSyncableOutputStream fout =
        new GoogleHadoopSyncableOutputStream(
            ghfs,
            ghfs.getGcsPath(objectPath),
            new FileSystem.Statistics(ghfs.getScheme()),
            CreateFileOptions.DEFAULT_OVERWRITE,
            SyncableOutputStreamOptions.DEFAULT,
            mockExecutorService);

    IOException fakeIoException = new IOException("fake io exception");
    when(mockExecutorService.submit(any(Callable.class)))
        .thenReturn(Futures.immediateFailedFuture(new ExecutionException(fakeIoException)));

    byte[] data1 = {0x0f, 0x0e, 0x0e, 0x0d};
    byte[] data2 = {0x0b, 0x0e, 0x0e, 0x0f};

    fout.write(data1, 0, data1.length);
    fout.hsync(); // This one commits straight into destination.
    fout.write(data2, 0, data2.length);
    fout.hsync(); // This one enqueues the delete, but doesn't propagate exception yet.

    verify(mockExecutorService).submit(any(Callable.class));

    IOException thrown = assertThrows(IOException.class, fout::close);
    assertThat(thrown).hasCauseThat().hasMessageThat().contains(fakeIoException.getMessage());

    verify(mockExecutorService, times(2)).submit(any(Callable.class));
  }

  @Test
  public void testCloseTwice() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object.txt"));
    FSDataOutputStream fout = ghfs.create(objectPath);
    fout.close();
    fout.close(); // Fine to close twice.
  }

  @Test
  public void testWrite1AfterClose() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object.txt"));
    FSDataOutputStream fout = ghfs.create(objectPath);

    fout.close();
    assertThrows(ClosedChannelException.class, () -> fout.write(42));
  }

  @Test
  public void testWriteAfterClose() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("dir/object.txt"));
    FSDataOutputStream fout = ghfs.create(objectPath);
    fout.close();

    assertThrows(ClosedChannelException.class, () -> fout.write(new byte[] {0x01}, 0, 1));
  }

  @Test
  public void testSyncAfterClose() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object.txt"));
    FSDataOutputStream fout = ghfs.create(objectPath);
    fout.close();

    assertThrows(ClosedChannelException.class, fout::hsync);
  }

  @Test
  public void testSyncComposite_withLargeNumberOfComposeComponents() throws Exception {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object.txt"));

    // number of compose components should be greater than 1024 (previous limit for GCS compose API)
    byte[] expected = new byte[1536];
    new Random().nextBytes(expected);

    try (FSDataOutputStream fout = ghfs.create(objectPath)) {
      for (int i = 0; i < expected.length; ++i) {
        fout.write(expected, i, 1);
        fout.hsync();
      }
    }

    assertThat(readFile(objectPath)).isEqualTo(expected);
  }

  @Test
  public void hflush_rateLimited_writesEverything() throws Exception {
    ghfs.getConf().setEnum(GCS_OUTPUT_STREAM_TYPE.getKey(), OutputStreamType.FLUSHABLE_COMPOSITE);
    ghfs.getConf()
        .setLong(GCS_OUTPUT_STREAM_SYNC_MIN_INTERVAL_MS.getKey(), Duration.ofDays(1).toMillis());

    Path objectPath = new Path(ghfs.getUri().resolve("/hflush_rateLimited_writesEverything.bin"));

    byte[] testData = new byte[100];
    new Random().nextBytes(testData);

    try (FSDataOutputStream out = ghfs.create(objectPath)) {
      for (byte testDataByte : testData) {
        out.write(testDataByte);
        out.hflush();

        // Validate partly composed data always just contain the first byte because only the
        // first hflush() succeeds and all subsequent hflush() calls should be rate limited.
        assertThat(ghfs.getFileStatus(objectPath).getLen()).isEqualTo(1);
        assertThat(readFile(objectPath)).isEqualTo(new byte[] {testData[0]});
      }
    }

    // Assert that data was fully written after close
    assertThat(ghfs.getFileStatus(objectPath).getLen()).isEqualTo(testData.length);
    assertThat(readFile(objectPath)).isEqualTo(testData);
  }

  @Test
  public void testWriteStatistics() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object2.txt"));
    FileSystem.Statistics statistics = new FileSystem.Statistics(ghfs.getScheme());
    GoogleHadoopSyncableOutputStream fout =
        new GoogleHadoopSyncableOutputStream(
            ghfs,
            ghfs.getGcsPath(objectPath),
            statistics,
            CreateFileOptions.DEFAULT_OVERWRITE,
            SyncableOutputStreamOptions.DEFAULT,
            mockExecutorService);

    byte[] data1 = {0x0f, 0x0e, 0x0e, 0x0d};
    byte[] data2 = {0x0b, 0x0d, 0x0e, 0x0e, 0x0f};

    fout.write(data1, 0, data1.length);
    fout.hsync();
    assertThat(statistics.getBytesWritten()).isEqualTo(4);
    assertThat(statistics.getWriteOps()).isEqualTo(1);
    fout.write(data2, 0, data2.length);
    fout.hsync();
    assertThat(statistics.getBytesWritten()).isEqualTo(9);
    assertThat(statistics.getWriteOps()).isEqualTo(2);

    verify(mockExecutorService).submit(any(Callable.class));
  }

  @Test
  public void testStatistics() throws IOException {
    Path objectPath = new Path(ghfs.getUri().resolve("/dir/object2.txt"));
    FileSystem.Statistics statistics = new FileSystem.Statistics(ghfs.getScheme());
    GoogleHadoopSyncableOutputStream fout =
        new GoogleHadoopSyncableOutputStream(
            ghfs,
            ghfs.getGcsPath(objectPath),
            statistics,
            CreateFileOptions.DEFAULT_OVERWRITE,
            SyncableOutputStreamOptions.DEFAULT,
            mockExecutorService);

    byte[] data1 = {0x0f, 0x0e, 0x0e, 0x0d};
    byte[] data2 = {0x0b, 0x0d, 0x0e, 0x0e, 0x0f};

    fout.write(data1, 0, data1.length);
    fout.hsync();
    assertThat(fout.getStatistics().getIOStatistics().counters().get(INVOCATION_HFLUSH.getSymbol()))
        .isEqualTo(0);
    fout.write(data2, 0, data2.length);
    fout.hflush();
    assertThat(fout.getStatistics().getIOStatistics().counters().get(INVOCATION_HFLUSH.getSymbol()))
        .isEqualTo(1);
  }

  private byte[] readFile(Path objectPath) throws IOException {
    FileStatus status = ghfs.getFileStatus(objectPath);
    ByteArrayOutputStream allReadBytes = new ByteArrayOutputStream(toIntExact(status.getLen()));
    byte[] readBuffer = new byte[1024 * 1024];
    try (FSDataInputStream in = ghfs.open(objectPath)) {
      int readBytes;
      while ((readBytes = in.read(readBuffer)) > 0) {
        allReadBytes.write(readBuffer, 0, readBytes);
      }
    }
    return allReadBytes.toByteArray();
  }
}
