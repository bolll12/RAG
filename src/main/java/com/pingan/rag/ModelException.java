package com.pingan.rag;

public class ModelException extends RuntimeException {
    public ModelException(String message) { super(message); }
    public ModelException(String message, Throwable cause) { super(message, cause); }
}
