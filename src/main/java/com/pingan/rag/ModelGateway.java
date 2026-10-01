package com.pingan.rag;

import java.util.List;

public interface ModelGateway {
    List<double[]> embed(List<String> texts);
    String answer(String question, String evidence);
}
