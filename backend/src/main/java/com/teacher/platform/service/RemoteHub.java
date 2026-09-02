package com.teacher.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 手机遥控 SSE 通道（零新依赖，spring-web 自带 SseEmitter）。
 *
 * 通道模型：手机端只发指令（低频 POST /remote/cmd），PC/手机端经 SSE 长连接收事件。
 * 同账号（uid）即同一房间，一个老师可同时挂多个连接（PC 投影 + 手机），指令广播给全部连接。
 * 鉴权：SSE 的 EventSource 无法设置 Authorization 头，故先经 JWT 接口换 60 秒一次性 ticket，
 * 再凭 ticket 建立 EventSource 连接，避免 token 出现在 URL 日志中。
 */
@Component
public class RemoteHub {

    private static final Logger log = LoggerFactory.getLogger(RemoteHub.class);

    /** ticket 有效期：60 秒（仅用于换连接，过期重新申请） */
    private static final long TICKET_TTL_MS = 60_000;
    /** 心跳间隔：25 秒发 SSE 注释行保活（nginx 默认 60s 空闲断连），顺带清理死连接 */
    private static final long HEARTBEAT_SEC = 25;

    /** 房间：uid -> 该账号在线的 SSE 连接列表 */
    private final Map<Long, List<SseEmitter>> rooms = new ConcurrentHashMap<>();
    /** 一次性 ticket：ticket -> {uid, 过期时间戳} */
    private final Map<String, long[]> tickets = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();

    public RemoteHub() {
        ScheduledExecutorService pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "remote-sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        pool.scheduleAtFixedRate(this::heartbeat, HEARTBEAT_SEC, HEARTBEAT_SEC, TimeUnit.SECONDS);
    }

    /** 生成 60 秒有效的一次性 ticket */
    public String newTicket(Long uid) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new long[]{uid, System.currentTimeMillis() + TICKET_TTL_MS});
        return ticket;
    }

    /** 校验并消费 ticket（一次性），返回 uid；无效/过期返回 null */
    public Long consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        long[] v = tickets.remove(ticket);
        if (v == null) return null;
        if (System.currentTimeMillis() > v[1]) return null;
        return v[0];
    }

    /** 建立 SSE 连接并注册到房间；返回的 emitter 由 Spring MVC 持有 */
    public SseEmitter connect(Long uid) {
        // timeout=0 表示不超时；靠心跳保活、前端 EventSource 断线重连
        SseEmitter emitter = new SseEmitter(0L);
        rooms.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(uid, emitter));
        emitter.onTimeout(() -> remove(uid, emitter));
        emitter.onError(e -> remove(uid, emitter));
        try {
            // 连上即发 connected，前端据此进入"遥控在线"状态（pending 进化攒队列不自动播）
            emitter.send(SseEmitter.event().name("cmd").data("{\"action\":\"connected\"}", MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            remove(uid, emitter);
        }
        return emitter;
    }

    /** 向某账号的所有在线连接广播一条指令 */
    public void dispatch(Long uid, String action, Object payload) {
        List<SseEmitter> list = rooms.get(uid);
        if (list == null || list.isEmpty()) return;
        String body;
        try {
            body = json.writeValueAsString(Map.of("action", action, "payload", payload == null ? Map.of() : payload));
        } catch (Exception e) {
            log.warn("遥控指令序列化失败 action={}: {}", action, e.getMessage());
            return;
        }
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name("cmd").data(body, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                remove(uid, em);
            }
        }
    }

    /** 某账号当前在线连接数（调试/状态用） */
    public int onlineCount(Long uid) {
        List<SseEmitter> list = rooms.get(uid);
        return list == null ? 0 : list.size();
    }

    private void remove(Long uid, SseEmitter em) {
        List<SseEmitter> list = rooms.get(uid);
        if (list != null) list.remove(em);
    }

    /** 心跳：清理过期 ticket，向所有连接发注释行 :ping（EventSource 自动忽略） */
    private void heartbeat() {
        tickets.entrySet().removeIf(en -> System.currentTimeMillis() > en.getValue()[1]);
        for (Map.Entry<Long, List<SseEmitter>> en : rooms.entrySet()) {
            for (SseEmitter em : en.getValue()) {
                try {
                    em.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    remove(en.getKey(), em);
                }
            }
        }
    }
}
