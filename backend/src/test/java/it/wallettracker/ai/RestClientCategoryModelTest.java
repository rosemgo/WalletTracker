package it.wallettracker.ai;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Le prove di {@link CategoryModelContractTest} sulla versione con RestClient. */
class RestClientCategoryModelTest extends CategoryModelContractTest {

    @Override
    CategoryModel create(RestClient.Builder builder) {
        return new RestClientCategoryModel(builder.baseUrl(OLLAMA).build(), new JsonMapper());
    }
}
