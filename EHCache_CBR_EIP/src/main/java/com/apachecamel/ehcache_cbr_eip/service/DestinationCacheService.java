package com.apachecamel.ehcache_cbr_eip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ehcache.Cache;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.*;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Map;

@Service
public class DestinationCacheService {

    private Cache<String, String> cache;

    @PostConstruct
    public void loadConfig() throws Exception {

        CacheManager cacheManager =
                CacheManagerBuilder.newCacheManagerBuilder().build(true);

        cache = cacheManager.createCache(
                "destinationCache",
                CacheConfigurationBuilder.newCacheConfigurationBuilder(
                        String.class,
                        String.class,
                        ResourcePoolsBuilder.heap(100)
                )
        );

        ObjectMapper mapper = new ObjectMapper();

        InputStream inputStream =
                getClass().getClassLoader()
                        .getResourceAsStream("config/destination-config.json");

        Map<String, String> configMap =
                mapper.readValue(inputStream, Map.class);

        configMap.forEach(cache::put);

    }

    public String getDestination(String type) {
        return cache.get(type);
    }
}
