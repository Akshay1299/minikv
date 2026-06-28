package dev.akshay.minikv;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BloomFilterTest {

    @Test
    void noFalseNegatives() {
        BloomFilter bf = BloomFilter.create(1000, 10);
        for (int i = 0; i < 1000; i++) {
            bf.add(("key" + i).getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < 1000; i++) {
            assertTrue(bf.mightContain(("key" + i).getBytes(StandardCharsets.UTF_8)),
                    "added key must always test positive");
        }
    }

    @Test
    void falsePositiveRateIsReasonable() {
        BloomFilter bf = BloomFilter.create(1000, 10);
        for (int i = 0; i < 1000; i++) {
            bf.add(("present" + i).getBytes(StandardCharsets.UTF_8));
        }
        int fp = 0;
        for (int i = 0; i < 10000; i++) {
            if (bf.mightContain(("absent" + i).getBytes(StandardCharsets.UTF_8))) {
                fp++;
            }
        }
        assertTrue(fp < 500, "false positive rate should be well under 5%, was " + (fp / 100.0) + "%");
    }

    @Test
    void survivesSerializationRoundTrip() {
        BloomFilter bf = BloomFilter.create(100, 10);
        bf.add("hello".getBytes(StandardCharsets.UTF_8));
        BloomFilter restored = BloomFilter.deserialize(ByteBuffer.wrap(bf.serialize()));
        assertTrue(restored.mightContain("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
