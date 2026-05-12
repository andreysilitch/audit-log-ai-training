package com.example.audit.domain;

public class QueryValidationException extends RuntimeException {
  public QueryValidationException(String message) {
    super(message);
  }
}
