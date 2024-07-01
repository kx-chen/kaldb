package com.slack.astra.chunkManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.slack.astra.blobfs.s3.S3CrtBlobFs;
import com.slack.astra.blobfs.s3.S3TestUtils;
import com.slack.astra.chunk.Chunk;
import com.slack.astra.chunk.ReadOnlyChunkImpl;
import com.slack.astra.chunk.SearchContext;
import com.slack.astra.logstore.LogMessage;
import com.slack.astra.metadata.cache.CacheNodeAssignment;
import com.slack.astra.metadata.cache.CacheNodeAssignmentStore;
import com.slack.astra.metadata.cache.CacheNodeMetadata;
import com.slack.astra.metadata.cache.CacheNodeMetadataStore;
import com.slack.astra.metadata.core.CuratorBuilder;
import com.slack.astra.metadata.snapshot.SnapshotMetadata;
import com.slack.astra.metadata.snapshot.SnapshotMetadataStore;
import com.slack.astra.proto.config.AstraConfigs;
import com.slack.astra.proto.metadata.Metadata;
import com.slack.astra.testlib.SpanUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.curator.test.TestingServer;
import org.apache.curator.x.async.AsyncCuratorFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.s3.S3AsyncClient;

public class CachingChunkManagerTest {
  private static final String TEST_S3_BUCKET = "caching-chunkmanager-test";

  private TestingServer testingServer;
  private MeterRegistry meterRegistry;
  private S3CrtBlobFs s3CrtBlobFs;

  @RegisterExtension
  public static final S3MockExtension S3_MOCK_EXTENSION =
      S3MockExtension.builder()
          .withInitialBuckets(TEST_S3_BUCKET)
          .silent()
          .withSecureConnection(false)
          .build();

  private AsyncCuratorFramework curatorFramework;
  private CachingChunkManager<LogMessage> cachingChunkManager;

  @BeforeEach
  public void startup() throws Exception {
    meterRegistry = new SimpleMeterRegistry();
    testingServer = new TestingServer();

    S3AsyncClient s3AsyncClient =
        S3TestUtils.createS3CrtClient(S3_MOCK_EXTENSION.getServiceEndpoint());
    s3CrtBlobFs = new S3CrtBlobFs(s3AsyncClient);
  }

  @AfterEach
  public void shutdown() throws IOException, TimeoutException {
    if (cachingChunkManager != null) {
      cachingChunkManager.stopAsync();
      cachingChunkManager.awaitTerminated(15, TimeUnit.SECONDS);
    }
    if (curatorFramework != null) {
      curatorFramework.unwrap().close();
    }
    s3CrtBlobFs.close();
    testingServer.close();
    meterRegistry.close();
  }

  private CachingChunkManager<LogMessage> initChunkManager() throws TimeoutException {
    AstraConfigs.CacheConfig cacheConfig =
        AstraConfigs.CacheConfig.newBuilder()
            .setSlotsPerInstance(3)
            .setReplicaSet("rep1")
            .setDataDirectory(
                String.format(
                    "/tmp/%s/%s", this.getClass().getSimpleName(), RandomStringUtils.random(10)))
            .setServerConfig(
                AstraConfigs.ServerConfig.newBuilder()
                    .setServerAddress("localhost")
                    .setServerPort(8080)
                    .build())
            .setCapacityBytes(4096)
            .build();

    AstraConfigs.S3Config s3Config =
        AstraConfigs.S3Config.newBuilder()
            .setS3Bucket(TEST_S3_BUCKET)
            .setS3Region("us-east-1")
            .build();

    AstraConfigs.AstraConfig AstraConfig =
        AstraConfigs.AstraConfig.newBuilder()
            .setCacheConfig(cacheConfig)
            .setS3Config(s3Config)
            .build();

    AstraConfigs.ZookeeperConfig zkConfig =
        AstraConfigs.ZookeeperConfig.newBuilder()
            .setZkConnectString(testingServer.getConnectString())
            .setZkPathPrefix("test")
            .setZkSessionTimeoutMs(1000)
            .setZkConnectionTimeoutMs(1000)
            .setSleepBetweenRetriesMs(1000)
            .build();

    curatorFramework = CuratorBuilder.build(meterRegistry, zkConfig);

    CachingChunkManager<LogMessage> cachingChunkManager =
        new CachingChunkManager<>(
            meterRegistry,
            curatorFramework,
            s3CrtBlobFs,
            SearchContext.fromConfig(AstraConfig.getCacheConfig().getServerConfig()),
            AstraConfig.getS3Config().getS3Bucket(),
            AstraConfig.getCacheConfig().getDataDirectory(),
            AstraConfig.getCacheConfig().getReplicaSet(),
            AstraConfig.getCacheConfig().getSlotsPerInstance(),
            AstraConfig.getCacheConfig().getCapacityBytes());

    cachingChunkManager.startAsync();
    cachingChunkManager.awaitRunning(15, TimeUnit.SECONDS);
    return cachingChunkManager;
  }

  @Test
  public void shouldHandleLifecycle() throws Exception {
    cachingChunkManager = initChunkManager();

    assertThat(cachingChunkManager.getChunkList().size()).isEqualTo(3);

    List<Chunk<LogMessage>> readOnlyChunks = cachingChunkManager.getChunkList();
    await()
        .until(
            () ->
                ((ReadOnlyChunkImpl<?>) readOnlyChunks.get(0))
                    .getChunkMetadataState()
                    .equals(Metadata.CacheSlotMetadata.CacheSlotState.FREE));
    await()
        .until(
            () ->
                ((ReadOnlyChunkImpl<?>) readOnlyChunks.get(1))
                    .getChunkMetadataState()
                    .equals(Metadata.CacheSlotMetadata.CacheSlotState.FREE));
    await()
        .until(
            () ->
                ((ReadOnlyChunkImpl<?>) readOnlyChunks.get(2))
                    .getChunkMetadataState()
                    .equals(Metadata.CacheSlotMetadata.CacheSlotState.FREE));
  }

  @Test
  public void testAddMessageIsUnsupported() throws TimeoutException {
    cachingChunkManager = initChunkManager();
    assertThatThrownBy(() -> cachingChunkManager.addMessage(SpanUtil.makeSpan(1), 10, "1", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testCreatesChunksOnAssignment() throws Exception {
    System.setProperty("astra.ng.dynamicChunkSizes", "true");

    cachingChunkManager = initChunkManager();
    initAssignments();

    await()
        .timeout(10000, TimeUnit.MILLISECONDS)
        .until(() -> cachingChunkManager.getChunksMap().size() == 1);
    assertThat(cachingChunkManager.getChunksMap().size()).isEqualTo(1);

    // assert state is correct
    // assert searchMetadata is correct
    // assert can query
    System.setProperty("astra.ng.dynamicChunkSizes", "false");
  }

  @Test
  public void testChunkManagerRegistration() throws Exception {
    System.setProperty("astra.ng.dynamicChunkSizes", "true");

    cachingChunkManager = initChunkManager();
    CacheNodeMetadataStore cacheNodeMetadataStore = new CacheNodeMetadataStore(curatorFramework);

    List<CacheNodeMetadata> cacheNodeMetadatas = cacheNodeMetadataStore.listSync();
    assertThat(cachingChunkManager.getChunkList().size()).isEqualTo(0);
    assertThat(cacheNodeMetadatas.size()).isEqualTo(1);
    assertThat(cacheNodeMetadatas.getFirst().nodeCapacityBytes).isEqualTo(4096);
    assertThat(cacheNodeMetadatas.getFirst().replicaSet).isEqualTo("rep1");

    cachingChunkManager.shutDown();
    // assert chunks are gone

    cacheNodeMetadataStore.close();
    System.setProperty("astra.ng.dynamicChunkSizes", "false");
  }

  @Test
  public void testBasicChunkEviction() throws Exception {
    System.setProperty("astra.ng.dynamicChunkSizes", "true");

    cachingChunkManager = initChunkManager();
    initAssignments();

    // assert chunks created
    await()
        .timeout(10000, TimeUnit.MILLISECONDS)
        .until(() -> cachingChunkManager.getChunksMap().size() == 1);
    assertThat(cachingChunkManager.getChunksMap().size()).isEqualTo(1);

    // set state of assignment to evict
    // assert that chunks were evicted

    System.setProperty("astra.ng.dynamicChunkSizes", "false");
  }

  private void initAssignments() throws Exception {
    CacheNodeAssignmentStore cacheNodeAssignmentStore =
        new CacheNodeAssignmentStore(curatorFramework);
    SnapshotMetadataStore snapshotMetadataStore = new SnapshotMetadataStore(curatorFramework);
    snapshotMetadataStore.createSync(
        new SnapshotMetadata(
            "ijklmnop", TEST_S3_BUCKET, 1, 1, 0, "abcd", Metadata.IndexType.LOGS_LUCENE9, 0));
    CacheNodeAssignment newAssignment =
        new CacheNodeAssignment(
            "abcd",
            cachingChunkManager.getId(),
            "ijklmnop",
            "rep1",
            Metadata.CacheNodeAssignment.CacheNodeAssignmentState.LOADING);
    cacheNodeAssignmentStore.createSync(newAssignment);
  }

  // TODO: Add a unit test to ensure caching chunk manager can search messages.
  // TODO: Add a unit test to ensure that all chunks in caching chunk manager are read only.
  // TODO: Add a unit test to ensure that caching chunk manager can handle exceptions gracefully.
}
