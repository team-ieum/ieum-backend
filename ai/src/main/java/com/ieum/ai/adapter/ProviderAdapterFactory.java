package com.ieum.ai.adapter;

import com.ieum.common.exception.CustomException;
import com.ieum.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProviderAdapterFactory {

    private final List<ProviderAdapter> adapters;

    private Map<String, ProviderAdapter> adapterMap;

    @PostConstruct
    void init() {
        adapterMap = adapters.stream()
            .collect(Collectors.toMap(ProviderAdapter::getProvider, Function.identity()));
    }

    public ProviderAdapter getAdapter(String provider) {
        ProviderAdapter adapter = adapterMap.get(provider);
        if (adapter == null) {
            throw new CustomException(ErrorCode.INVALID_MODEL, "지원하지 않는 프로바이더: " + provider);
        }
        return adapter;
    }
}
