package io.github.sahil3801.gateway.common;

/** The X-Client-Id header has a value we won't accept. Becomes a 400. */
public class InvalidClientIdException extends RuntimeException {

    public InvalidClientIdException(String message) {
        super(message);
    }
}
