package com.library.users.application;

import com.library.users.domain.User;

public record LoginResult(User user, boolean created, String token) {}
