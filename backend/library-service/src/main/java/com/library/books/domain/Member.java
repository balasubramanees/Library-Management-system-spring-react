package com.library.books.domain;

/**
 * The authenticated caller, as seen by this bounded context. Identity is owned by user-service;
 * this service only keeps the id on loans and wait lists.
 */
public record Member(String id, String name, boolean admin) {}
