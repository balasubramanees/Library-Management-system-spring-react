package com.library.users.adapter.in.web;

import com.library.users.adapter.in.web.SessionDtos.LoginRequest;
import com.library.users.adapter.in.web.SessionDtos.LoginResponse;
import com.library.users.adapter.in.web.SessionDtos.MessageResponse;
import com.library.users.adapter.in.web.SessionDtos.UserResponse;
import com.library.users.application.LoginResult;
import com.library.users.application.SessionService;
import com.library.users.domain.User;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code login} / {@code logout} plus token introspection ({@code GET /sessions/current}), which the
 * library-service calls to authenticate every request.
 */
@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        LoginResult result = sessions.login(request.name());
        User user = result.user();
        List<String> messages = new ArrayList<>();
        messages.add("Hello, " + user.name() + "!");
        if (user.isAdmin()) {
            messages.add("You have access to library management.");
        }
        var body = new LoginResponse(result.token(), UserResponse.from(user), result.created(), messages);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/current")
    public UserResponse current(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return UserResponse.from(sessions.currentUser(BearerToken.from(authorization)));
    }

    @DeleteMapping("/current")
    public MessageResponse logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        User user = sessions.logout(BearerToken.from(authorization));
        return new MessageResponse("Goodbye, " + user.name() + "!");
    }
}
