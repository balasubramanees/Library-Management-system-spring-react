package com.library.users.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.library.users.domain.User;
import java.util.List;

/** Wire format of the session API. Kept separate from domain types so either can evolve. */
public final class SessionDtos {

    private SessionDtos() {}

    public record LoginRequest(String name) {}

    public record UserResponse(String id, String name, @JsonProperty("isAdmin") boolean isAdmin) {
        static UserResponse from(User user) {
            return new UserResponse(user.id(), user.name(), user.isAdmin());
        }
    }

    public record LoginResponse(String token, UserResponse user, boolean created, List<String> messages) {}

    public record MessageResponse(String message) {}
}
