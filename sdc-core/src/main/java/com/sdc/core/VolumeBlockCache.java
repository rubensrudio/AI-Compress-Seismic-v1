package com.sdc.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache LRU para blocos de volume 3D descomprimidos.
 * Evita re-decodificar o mesmo bloco múltiplas vezes durante navegação de slices.
 */
public final class VolumeBlockCache {

    private final int maxCapacity;
    private final LinkedHashMap<String, float[][][]> cache;

    public VolumeBlockCache(int maxCapacity) {
        if (maxCapacity < 1) {
            throw new IllegalArgumentException("maxCapacity must be >= 1");
        }
        this.maxCapacity = maxCapacity;
        this.cache = new LinkedHashMap<String, float[][][]>(maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > VolumeBlockCache.this.maxCapacity;
            }
        };
    }

    /** Retorna a chave do bloco (coordenadas + arquivo). */
    public static String key(String filePath, int ii, int jj, int tt) {
        return filePath + "|" + ii + "," + jj + "," + tt;
    }

    /** Tenta obter bloco do cache. */
    public float[][][] get(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    /** Armazena bloco no cache. */
    public void put(String key, float[][][] data) {
        synchronized (cache) {
            cache.put(key, data);
        }
    }

    /** Limpa o cache. */
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Retorna tamanho atual do cache. */
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Retorna capacidade máxima. */
    public int capacity() {
        return maxCapacity;
    }
}
