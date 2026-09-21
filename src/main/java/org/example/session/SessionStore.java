package org.example.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话存储：以「最多保留多少会话」为硬上限的 LRU 缓存
 *
 * <p>此前这里是无界的 ConcurrentHashMap —— 客户端每换一个 Id 就永久多一条记录，
 * 进程生命周期内从不释放，可被随机 session id 直接打爆堆。</p>
 */
@Component
public class SessionStore {

    private static final Logger logger = LoggerFactory.getLogger(SessionStore.class);

    /** 单个会话保留的历史消息对数上限（一问一答算一对） */
    static final int MAX_HISTORY_PAIRS = 6;

    private final int maxSessions;
    private final Map<String, SessionInfo> sessions;

    public SessionStore(@Value("${session.max-count:500}") int maxSessions) {
        this.maxSessions = Math.max(1, maxSessions);
        final int cap = this.maxSessions;

        // accessOrder=true 使 get/put 都刷新最近使用顺序，配合 removeEldestEntry 形成 LRU
        this.sessions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, SessionInfo> eldest) {
                if (size() <= cap) {
                    return false;
                }
                logger.info("会话数达到上限 {}，淘汰最久未使用的会话: {}", cap, eldest.getKey());
                return true;
            }
        });
    }

    /**
     * 取回会话；不存在则创建。传入空 Id 时分配一个随机 Id
     */
    public SessionInfo getOrCreate(String sessionId) {
        String id = (sessionId == null || sessionId.isEmpty()) ? UUID.randomUUID().toString() : sessionId;
        // 先 get 一次以刷新最近使用顺序，避免活跃会话被误淘汰
        SessionInfo existing = sessions.get(id);
        if (existing != null) {
            return existing;
        }
        return sessions.computeIfAbsent(id, SessionInfo::new);
    }

    /**
     * 只查找不创建。注意：底层是 accessOrder 的 LinkedHashMap，
     * 因此这次查找同样会刷新该会话的最近使用时间，使其不易被淘汰
     */
    public SessionInfo find(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    public int size() {
        return sessions.size();
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    /**
     * 单个会话的历史消息，线程安全
     */
    public static class SessionInfo {

        private final String sessionId;
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 追加一对消息（用户问题 + AI 回复），并裁剪到窗口内
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                int maxMessages = MAX_HISTORY_PAIRS * 2;
                while (messageHistory.size() > maxMessages) {
                    messageHistory.remove(0);
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * 返回历史副本，避免调用方并发改动内部状态
         */
        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getCreateTime() {
            return createTime;
        }
    }
}
