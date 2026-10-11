package com.cmcc.cmdevops.security.federation.vector;

import java.util.List;

public interface VectorSearchClient {

    List<VectorHit> search(String collection, String field, List<Double> vector, int k, String filter);
}
