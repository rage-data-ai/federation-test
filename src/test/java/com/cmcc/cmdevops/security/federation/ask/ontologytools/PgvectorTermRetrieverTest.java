package com.cmcc.cmdevops.security.federation.ask.ontologytools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cmcc.cmdevops.security.federation.ask.ontologytools.BusinessContextBuilder.Term;
import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class PgvectorTermRetrieverTest {

    @Test
    void disabledWhenUrlBlank() {
        FederationProperties.Ask ask = new FederationProperties.Ask();
        assertFalse(PgvectorTermRetriever.create(ask).enabled());
        assertFalse(PgvectorTermRetriever.create(null).enabled());
    }

    @Test
    void disabledWhenUrlUnreachable() {
        FederationProperties.Ask ask = new FederationProperties.Ask();
        ask.setPgvectorUrl("jdbc:postgresql://127.0.0.1:1/missing");
        ask.setPgvectorUsername("x");
        ask.setPgvectorPassword("y");
        assertFalse(PgvectorTermRetriever.create(ask).enabled());
    }

    @Test
    void vectorLiteralAndFit() {
        assertEquals("[1.0,0.5,-0.25]", PgvectorTermRetriever.toVectorLiteral(new float[] {1f, 0.5f, -0.25f}));
        PgvectorTermRetriever retriever = PgvectorTermRetriever.disabled();
        float[] fitted = retriever.fit(new float[] {3f, 4f});
        assertEquals(HashingTermEmbedder.DEFAULT_DIM, fitted.length);
        double norm = 0;
        for (float v : fitted) {
            norm += v * v;
        }
        assertTrue(norm > 0.99 && norm < 1.01);
    }

    @Test
    void disabledRetrieveIsEmpty() {
        List<Term> lexicon = List.of(new Term("Alert", "concept", "告警", "告警", "告警", "label", 1.0));
        assertTrue(PgvectorTermRetriever.disabled()
                .retrieve("告警", lexicon, new HashingTermEmbedder(), 5)
                .isEmpty());
    }
}
