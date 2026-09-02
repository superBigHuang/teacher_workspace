package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.RemoteHub;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 手机遥控通道：
 * - POST /remote/ticket：JWT 鉴权后换 60 秒一次性 ticket（EventSource 不能带 Authorization 头）
 * - GET  /remote/events：凭 ticket 建立 SSE 长连，接收同账号遥控指令（在拦截器白名单中）
 * - POST /remote/cmd：手机端发指令（JWT 鉴权），广播给同账号所有在线连接（PC/手机）
 */
@RestController
@RequestMapping("/api/remote")
public class RemoteController extends BaseController {

    private final RemoteHub hub;

    public RemoteController(RemoteHub hub) {
        this.hub = hub;
    }

    /** 申请 SSE 连接票据 */
    @PostMapping("/ticket")
    public Result<Map<String, Object>> ticket() {
        return Result.ok(Map.of("ticket", hub.newTicket(uid())));
    }

    /** SSE 长连：凭票接入，接收遥控指令事件流 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam(required = false) String ticket) {
        Long uid = hub.consumeTicket(ticket);
        if (uid == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ticket 无效或已过期");
        }
        return hub.connect(uid);
    }

    /** 发送遥控指令：action 如 evolve.playAll / evolve.play / pets.changed / navigate / spotlight */
    @PostMapping("/cmd")
    public Result<Void> cmd(@RequestBody Map<String, Object> body) {
        String action = body.get("action") == null ? "" : String.valueOf(body.get("action")).trim();
        if (action.isEmpty()) return Result.fail("缺少 action");
        hub.dispatch(uid(), action, body.get("payload"));
        return Result.ok();
    }
}
