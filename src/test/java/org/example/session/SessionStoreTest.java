package org.example.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话存储的内存上界与 LRU 语义
 *
 * <p>回归目标：修复前这里是无界 ConcurrentHashMap，任意客户端刷 Id 即可耗尽堆内存。</p>
 */
class SessionStoreTest {

    @Test
    void 超过上限后会话数不再增长() {
        SessionStore store = new SessionStore(10);
        for (int i = 0; i < 1000; i++) {
            store.getOrCreate("session-" + i);
        }
        assertThat(store.size()).isLessThanOrEqualTo(10);
    }

    @Test
    void 淘汰的是最久未使用的会话() {
        SessionStore store = new SessionStore(3);
        store.getOrCreate("a");
        store.getOrCreate("b");
        store.getOrCreate("c");

        // 触碰 a，使 b 成为最久未使用
        assertThat(store.find("a")).isNotNull();

        store.getOrCreate("d");

        assertThat(store.find("b")).isNull();
        assertThat(store.find("a")).isNotNull();
        assertThat(store.find("c")).isNotNull();
        assertThat(store.find("d")).isNotNull();
    }

    @Test
    void 活跃会话不会被误淘汰() {
        SessionStore store = new SessionStore(2);
        store.getOrCreate("hot");

        // 持续使用 hot，同时不断插入新会话
        for (int i = 0; i < 50; i++) {
            store.getOrCreate("hot").addMessage("q" + i, "a" + i);
            store.getOrCreate("noise-" + i);
        }

        assertThat(store.find("hot")).isNotNull();
        assertThat(store.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void 同一Id返回同一实例() {
        SessionStore store = new SessionStore(10);
        SessionStore.SessionInfo s1 = store.getOrCreate("x");
        SessionStore.SessionInfo s2 = store.getOrCreate("x");
        assertThat(s1).isSameAs(s2);
    }

    @Test
    void 空Id会分配随机Id且可再次取回() {
        SessionStore store = new SessionStore(10);
        SessionStore.SessionInfo created = store.getOrCreate(null);
        assertThat(created.getSessionId()).isNotBlank();

        created.addMessage("问题", "回答");
        assertThat(store.find(created.getSessionId())).isSameAs(created);

        assertThat(store.getOrCreate("").getSessionId()).isNotBlank();
    }

    @Test
    void find不会创建会话() {
        SessionStore store = new SessionStore(10);
        assertThat(store.find("missing")).isNull();
        assertThat(store.find(null)).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void 历史消息被裁剪到窗口上限() {
        SessionStore store = new SessionStore(10);
        SessionStore.SessionInfo s = store.getOrCreate("w");

        for (int i = 0; i < 50; i++) {
            s.addMessage("q" + i, "a" + i);
        }

        assertThat(s.getMessagePairCount()).isEqualTo(SessionStore.MAX_HISTORY_PAIRS);
        List<Map<String, String>> history = s.getHistory();
        assertThat(history).hasSize(SessionStore.MAX_HISTORY_PAIRS * 2);
        // 保留的应是最晚的一批
        assertThat(history.get(history.size() - 2).get("content")).isEqualTo("q49");
        assertThat(history.get(history.size() - 1).get("content")).isEqualTo("a49");
    }

    @Test
    void getHistory返回副本不影响内部状态() {
        SessionStore store = new SessionStore(10);
        SessionStore.SessionInfo s = store.getOrCreate("copy");
        s.addMessage("q", "a");

        List<Map<String, String>> snapshot = s.getHistory();
        snapshot.clear();

        assertThat(s.getMessagePairCount()).isEqualTo(1);
    }

    @Test
    void 并发刷Id也不会突破上限() throws Exception {
        final int cap = 20;
        SessionStore store = new SessionStore(cap);
        int threads = 8, perThread = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.getOrCreate("t" + tid + "-" + i).addMessage("q", "a");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        assertThat(store.size()).isLessThanOrEqualTo(cap);
    }

    @Test
    void 上限为0或负数时退化为保留一个而非崩溃() {
        assertThat(new SessionStore(0).getMaxSessions()).isEqualTo(1);
        assertThat(new SessionStore(-5).getMaxSessions()).isEqualTo(1);
    }
}
