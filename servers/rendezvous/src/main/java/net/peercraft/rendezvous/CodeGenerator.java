package net.peercraft.rendezvous;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Generates short human-friendly codes from a symbol-safe alphabet (no easily confused
 * characters like 0/O or 1/I/L). Shared by room codes and account friend codes — both
 * want the same "read it over voice chat" properties.
 */
public final class CodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final int length;

    public CodeGenerator(int length) {
        this.length = length;
    }

    public String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Regenerates until {@code taken} reports the candidate is free. */
    public String generateUnique(Predicate<String> taken) {
        String code;
        do {
            code = generate();
        } while (taken.test(code));
        return code;
    }
}
