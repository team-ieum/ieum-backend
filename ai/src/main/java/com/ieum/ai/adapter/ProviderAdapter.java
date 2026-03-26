package com.ieum.ai.adapter;

import com.ieum.ai.adapter.model.LlmRequest;
import com.ieum.ai.adapter.model.LlmResponse;
import com.ieum.ai.adapter.model.ModelInfo;
import java.util.List;

public interface ProviderAdapter {

    String getProvider();

    LlmResponse chat(LlmRequest request);

    boolean validateCredential(String decryptedKey);

    List<ModelInfo> listModels();
}
