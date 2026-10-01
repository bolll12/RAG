package com.pingan.rag;

@FunctionalInterface
interface WikiModel {
    String generate(String system, String input);
}
