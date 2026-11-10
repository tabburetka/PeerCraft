package net.peercraft.rendezvous;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CodeGeneratorTest {

    @Test
    void generateProducesCodeOfRequestedLength() {
        CodeGenerator generator = new CodeGenerator(6);

        assertEquals(6, generator.generate().length());
    }

    @Test
    void generateOnlyUsesSymbolSafeAlphabetCharacters() {
        CodeGenerator generator = new CodeGenerator(6);

        for (int i = 0; i < 200; i++) {
            String code = generator.generate();
            for (char c : code.toCharArray()) {
                assertTrue("ABCDEFGHJKMNPQRSTUVWXYZ23456789".indexOf(c) >= 0,
                        "Unexpected character '" + c + "' in generated code " + code);
            }
        }
    }

    @Test
    void generateUniqueRetriesUntilPredicateReportsCandidateAsFree() {
        // A predicate that rejects the first two candidates it's asked about must not make
        // generateUnique give up or return one of them — it should keep retrying until a
        // genuinely free candidate comes up.
        CodeGenerator generator = new CodeGenerator(6);
        Set<String> seenCandidates = new HashSet<>();
        AtomicInteger rejectCount = new AtomicInteger(0);

        String result = generator.generateUnique(candidate -> {
            seenCandidates.add(candidate);
            return rejectCount.getAndIncrement() < 2;
        });

        assertTrue(seenCandidates.contains(result));
        assertTrue(rejectCount.get() >= 3, "predicate should have been consulted for a 3rd, accepted candidate");
    }
}
