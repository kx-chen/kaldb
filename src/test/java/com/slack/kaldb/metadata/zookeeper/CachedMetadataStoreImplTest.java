package com.slack.kaldb.metadata.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.slack.kaldb.metadata.core.MetadataSerializer;
import com.slack.kaldb.metadata.snapshot.SnapshotMetadata;
import com.slack.kaldb.metadata.snapshot.SnapshotMetadataSerializer;
import com.slack.kaldb.util.CountingFatalErrorHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// TODO: Rename this test CachedMetadataImplTest?
public class CachedMetadataStoreImplTest {

  private TestingServer testingServer;
  private ZookeeperMetadataStoreImpl metadataStore;
  private MeterRegistry meterRegistry;
  private final SnapshotMetadataSerializer serDe = new SnapshotMetadataSerializer();

  @Before
  public void setUp() throws Exception {
    meterRegistry = new SimpleMeterRegistry();
    // NOTE: Sometimes the ZK server fails to start. Handle it more gracefully, if tests are flaky.
    testingServer = new TestingServer();
    CountingFatalErrorHandler countingFatalErrorHandler = new CountingFatalErrorHandler();
    metadataStore =
        new ZookeeperMetadataStoreImpl(
            testingServer.getConnectString(),
            "test",
            1000,
            1000,
            new RetryNTimes(1, 500),
            countingFatalErrorHandler,
            meterRegistry);
  }

  @After
  public void tearDown() throws IOException {
    metadataStore.close();
    testingServer.close();
    meterRegistry.close();
  }

  private SnapshotMetadata makeSnapshot(String name) {
    return new SnapshotMetadata(name, "/testPath_" + name, name + "snapshotId", 1, 100, 1, "1");
  }

  // TODO: Consider using the ZK store config?
  private CachedMetadataStore<SnapshotMetadata> makeCachedStore(
      String path,
      CachedMetadataStoreListener listener,
      MetadataSerializer<SnapshotMetadata> metadataSerializer)
      throws Exception {

    CachedMetadataStore<SnapshotMetadata> cachedMetadataStore =
        new CachedMetadataStoreImpl<>(
            path,
            metadataSerializer,
            metadataStore.getCurator(),
            metadataStore.getMetadataExecutorService());
    cachedMetadataStore.start();
    return cachedMetadataStore;
  }

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Test
  public void watchCachePersistentTreeTest() throws Exception {
    String root = "/root";
    assertThat(metadataStore.create(root, "", true).get()).isNull();
    CachedMetadataStore<SnapshotMetadata> cache = makeCachedStore("/root", null, serDe);

    String path1 = "/root/1";
    SnapshotMetadata snapshot1 = makeSnapshot("test1");
    assertThat(metadataStore.create(path1, serDe.toJsonStr(snapshot1), true).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isSameAs(1));
    assertThat(metadataStore.get(path1).get()).isEqualTo(serDe.toJsonStr(snapshot1));
    assertThat(cache.get("1").get()).isEqualTo(snapshot1);
    assertThat(cache.getInstances().get(0)).isEqualTo(snapshot1);

    String path2 = "/root/2";
    SnapshotMetadata snapshot2 = makeSnapshot("test2");
    assertThat(metadataStore.create(path2, serDe.toJsonStr(snapshot2), true).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(2));
    assertThat(metadataStore.get(path2).get()).isEqualTo(serDe.toJsonStr(snapshot2));
    assertThat(cache.get("2").get()).isEqualTo(snapshot2);
    assertThat(cache.getInstances()).contains(snapshot1, snapshot2);

    String path3 = "/root/3";
    SnapshotMetadata snapshot3 = makeSnapshot("test3");
    assertThat(metadataStore.create(path3, serDe.toJsonStr(snapshot3), true).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(3));
    assertThat(metadataStore.get(path3).get()).isEqualTo(serDe.toJsonStr(snapshot3));
    assertThat(cache.get("3").get()).isEqualTo(snapshot3);
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2, snapshot3);

    // TODO: Creating nesting when adding objects throws an exception in the cache since the nested
    //  object
    //  doesn't have a KalDbMetdata Type. Add a unit test to test for this use case.
    // TODO: Always ensure createPath is false in the nested calls?
    // TODO: Don't allow nested paths in cached objects?
    // TODO: Move below code into it's own unit test because of above issues?
    /*
    // Nesting in existing paths are also cached.
    String path333 = "/root/3/33/333";
    SnapshotMetadata snapshot333 = makeSnapshot("test333");
    assertThat(metadataStore.create(path333, serDe.toJsonStr(snapshot333), true).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(4));
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2, snapshot3, snapshot333);
    */

    // TODO: Add metrics on cache metadata updates.
    // Updating data in a node refreshes the cache.
    SnapshotMetadata snapshot31 = makeSnapshot("snapshot31");
    assertThat(metadataStore.put(path3, serDe.toJsonStr(snapshot31)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().contains(snapshot31)).isTrue());
    assertThat(metadataStore.get(path3).get()).isEqualTo(serDe.toJsonStr(snapshot31));
    assertThat(cache.get("3").get()).isEqualTo(snapshot31);
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2, snapshot31);

    // Deleting a node refreshes the cache.
    assertThat(metadataStore.delete(path3).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(2));
    assertThat(cache.get(path3).isPresent()).isFalse();
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2);

    cache.close();
  }

  // TODO: Add a test with a counting listener.
  // TODO: Add a test with server shutdown and timeout.
  // TODO: Add a test with server shutdown and timeout with ephemeral node.

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Test
  public void testWatchCacheEphemeralTreeTest() throws Exception {
    String root = "/eroot";
    assertThat(metadataStore.create(root, "", true).get()).isNull();
    CachedMetadataStore<SnapshotMetadata> cache = makeCachedStore(root, null, serDe);

    String path1 = "/eroot/ephemeral1";
    SnapshotMetadata snapshot1 = makeSnapshot("test1");
    assertThat(metadataStore.createEphemeralNode(path1, serDe.toJsonStr(snapshot1)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isSameAs(1));
    assertThat(metadataStore.get(path1).get()).isEqualTo(serDe.toJsonStr(snapshot1));
    assertThat(cache.get("ephemeral1").get()).isEqualTo(snapshot1);
    assertThat(cache.getInstances().get(0)).isEqualTo(snapshot1);

    String path2 = "/eroot/ephemeral2";
    SnapshotMetadata snapshot2 = makeSnapshot("test2");
    assertThat(metadataStore.createEphemeralNode(path2, serDe.toJsonStr(snapshot2)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(2));
    assertThat(metadataStore.get(path2).get()).isEqualTo(serDe.toJsonStr(snapshot2));
    assertThat(cache.get("ephemeral2").get()).isEqualTo(snapshot2);
    assertThat(cache.getInstances()).contains(snapshot1, snapshot2);

    String path3 = "/eroot/ephemeral3";
    SnapshotMetadata snapshot3 = makeSnapshot("test3");
    assertThat(metadataStore.createEphemeralNode(path3, serDe.toJsonStr(snapshot3)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(3));
    assertThat(metadataStore.get(path3).get()).isEqualTo(serDe.toJsonStr(snapshot3));
    assertThat(cache.get("ephemeral3").get()).isEqualTo(snapshot3);
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2, snapshot3);

    // TODO: Add metrics on cache metadata updates.
    // Updating ephemeral data in a node refreshes the cache.
    SnapshotMetadata snapshot31 = makeSnapshot("snapshot31");
    assertThat(metadataStore.put(path3, serDe.toJsonStr(snapshot31)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().contains(snapshot31)).isTrue());
    assertThat(metadataStore.get(path3).get()).isEqualTo(serDe.toJsonStr(snapshot31));
    assertThat(cache.get("ephemeral3").get()).isEqualTo(snapshot31);
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2, snapshot31);

    // Deleting ephemeral node refreshes the cache.
    assertThat(metadataStore.delete(path3).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.getInstances().size()).isEqualTo(2));
    assertThat(cache.get(path3).isPresent()).isFalse();
    assertThat(cache.getInstances()).containsOnly(snapshot1, snapshot2);

    // TODO: test node expiry
    cache.close();

    // TODO: Test cache close semantics.
  }

  @Test
  public void testCacheOnNonExistentNode() throws Exception {
    final String root = "/root123";
    makeCachedStore(root, null, serDe);
    assertThat(metadataStore.exists(root).get()).isTrue();
  }

  // TODO: This test seems redundant?
  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Test
  public void testWatchingPersistentNode() throws Exception {
    final String root = "/root";
    assertThat(metadataStore.create(root, "", true).get()).isNull();

    final String node = "/root/node";
    SnapshotMetadata snapshot1 = makeSnapshot("test1");
    assertThat(metadataStore.create(node, serDe.toJsonStr(snapshot1), true).get()).isNull();

    CachedMetadataStore<SnapshotMetadata> cache = makeCachedStore(root, null, serDe);

    await().untilAsserted(() -> assertThat(cache.get("node").get()).isEqualTo(snapshot1));
    assertThat(cache.getInstances().size()).isEqualTo(1);
    assertThat(metadataStore.exists(node).get()).isTrue();
    assertThat(metadataStore.get(node).get()).isEqualTo(serDe.toJsonStr(snapshot1));

    SnapshotMetadata snapshot11 = makeSnapshot("test11");
    assertThat(metadataStore.put(node, serDe.toJsonStr(snapshot11)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.get("node").get()).isEqualTo(snapshot11));
    assertThat(cache.getInstances().size()).isEqualTo(1);

    assertThat(metadataStore.delete(node).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.get(node).isPresent()).isFalse());
    assertThat(cache.getInstances()).isEmpty();

    cache.close();
  }

  // TODO: This test seems redundant?
  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Test
  public void testWatchingEphermeralNode() throws Exception {
    final String root = "/root";
    assertThat(metadataStore.create(root, "", true).get()).isNull();

    final String node = "/root/enode";
    SnapshotMetadata snapshot1 = makeSnapshot("test1");
    assertThat(metadataStore.createEphemeralNode(node, serDe.toJsonStr(snapshot1)).get()).isNull();

    CachedMetadataStore<SnapshotMetadata> cache = makeCachedStore(root, null, serDe);
    await().untilAsserted(() -> assertThat(cache.get("enode").get()).isEqualTo(snapshot1));
    assertThat(cache.getInstances().size()).isEqualTo(1);
    assertThat(metadataStore.exists(node).get()).isTrue();
    assertThat(metadataStore.get(node).get()).isEqualTo(serDe.toJsonStr(snapshot1));

    SnapshotMetadata snapshot11 = makeSnapshot("test11");
    assertThat(metadataStore.put(node, serDe.toJsonStr(snapshot11)).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.get("enode").get()).isEqualTo(snapshot11));
    assertThat(cache.getInstances().size()).isEqualTo(1);

    assertThat(metadataStore.delete(node).get()).isNull();
    await().untilAsserted(() -> assertThat(cache.get("enode").isPresent()).isFalse());
    assertThat(cache.getInstances()).isEmpty();

    // TODO: test node expiry
    cache.close();
  }
}