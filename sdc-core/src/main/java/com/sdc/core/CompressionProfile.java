package com.sdc.core;

/**
 * Define o "perfil" de compressão:
 *  - effectiveBits: quantos bits efetivos manter na quantização (1..16)
 *  - deflaterLevel: nível de compressão do Deflater (0..9)
 *
 * Observação importante:
 *  - Mais bits -> mais fidelidade, menos compressão.
 *  - Menos bits -> mais perda, mais redundância, melhor compressão.
 */
public final class CompressionProfile {

    private final int effectiveBits;
    private final int deflaterLevel;
    private final double fidelityPercentRequested;

    public CompressionProfile(int effectiveBits, int deflaterLevel, double fidelityPercentRequested) {
        if (effectiveBits < 1 || effectiveBits > 16) {
            throw new IllegalArgumentException("effectiveBits must be between 1 and 16");
        }
        if (deflaterLevel < 0 || deflaterLevel > 9) {
            throw new IllegalArgumentException("deflaterLevel must be between 0 and 9");
        }
        this.effectiveBits = effectiveBits;
        this.deflaterLevel = deflaterLevel;
        this.fidelityPercentRequested = fidelityPercentRequested;
    }

    public int effectiveBits() {
        return effectiveBits;
    }

    public int deflaterLevel() {
        return deflaterLevel;
    }

    public double fidelityPercentRequested() {
        return fidelityPercentRequested;
    }

    // --------- FÁBRICAS ---------

    /** Perfil default: alta qualidade. */
    public static CompressionProfile defaultHighQuality() {
        return new CompressionProfile(16, 9, 100.0);
    }

    /** Perfil "balanceado". */
    public static CompressionProfile balanced() {
        return new CompressionProfile(12, 7, 90.0);
    }

    /** Perfil "alta compressão". */
    public static CompressionProfile highCompression() {
        return new CompressionProfile(8, 6, 75.0);
    }

    /**
     * Cria um profile a partir de um "percentual de fidelidade" de 0 a 100.
     * Ex.: 100% -> ~16 bits, 50% -> ~8-10 bits, 10% -> bem agressivo.
     *
     * Não é uma relação física perfeita, mas é um "knob" intuitivo:
     * mais fidelidade -> mais bits.
     */
    public static CompressionProfile fromFidelityPercent(double fidelityPercent) {
        double f = Double.isNaN(fidelityPercent) ? 100.0 : fidelityPercent;
        if (f < 0.0) f = 0.0;
        if (f > 100.0) f = 100.0;

        // Mapear 0..100% para 4..16 bits (mínimo 4 bits pra não virar ruído total)
        int minBits = 4;
        int maxBits = 16;
        int bits = (int) Math.round(minBits + (maxBits - minBits) * (f / 100.0));

        // Nível do deflater: para fidelidade menor, podemos aceitar nível menor (menos CPU).
        int level;
        if (f > 95.0) {
            level = 9;
        } else if (f > 80.0) {
            level = 7;
        } else if (f > 50.0) {
            level = 6;
        } else {
            level = 5;
        }

        return new CompressionProfile(bits, level, f);
    }

    /** A partir de um nome de profile textual. */
    public static CompressionProfile fromProfileName(String profileName) {
        if (profileName == null) return defaultHighQuality();
        String p = profileName.trim().toUpperCase();
        return switch (p) {
            case "HIGH_COMPRESSION" -> highCompression();
            case "BALANCED" -> balanced();
            case "HIGH_QUALITY", "HQ" -> defaultHighQuality();
            default -> defaultHighQuality();
        };
    }
}
