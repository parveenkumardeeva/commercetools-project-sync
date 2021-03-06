package com.commercetools.project.sync;

import static com.commercetools.project.sync.util.ClientConfigurationUtils.createClient;
import static com.commercetools.project.sync.util.IntegrationTestUtils.assertCategoryExists;
import static com.commercetools.project.sync.util.IntegrationTestUtils.assertProductTypeExists;
import static com.commercetools.project.sync.util.IntegrationTestUtils.cleanUpProjects;
import static com.commercetools.project.sync.util.IntegrationTestUtils.createAttributeObject;
import static com.commercetools.project.sync.util.IntegrationTestUtils.createReferenceObject;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_SOURCE_CLIENT_CONFIG;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_TARGET_CLIENT_CONFIG;
import static com.commercetools.project.sync.util.TestUtils.assertCartDiscountSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertCategorySyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertCustomObjectSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertCustomerSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertInventoryEntrySyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertProductSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertProductTypeSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertShoppingListSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertStateSyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertTaxCategorySyncerLoggingEvents;
import static com.commercetools.project.sync.util.TestUtils.assertTypeSyncerLoggingEvents;
import static io.sphere.sdk.models.LocalizedString.ofEnglish;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sphere.sdk.categories.Category;
import io.sphere.sdk.categories.CategoryDraft;
import io.sphere.sdk.categories.CategoryDraftBuilder;
import io.sphere.sdk.categories.commands.CategoryCreateCommand;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.customobjects.CustomObject;
import io.sphere.sdk.customobjects.CustomObjectDraft;
import io.sphere.sdk.customobjects.commands.CustomObjectUpsertCommand;
import io.sphere.sdk.products.Product;
import io.sphere.sdk.products.ProductDraft;
import io.sphere.sdk.products.ProductDraftBuilder;
import io.sphere.sdk.products.ProductVariant;
import io.sphere.sdk.products.ProductVariantDraft;
import io.sphere.sdk.products.ProductVariantDraftBuilder;
import io.sphere.sdk.products.attributes.AttributeConstraint;
import io.sphere.sdk.products.attributes.AttributeDefinitionDraft;
import io.sphere.sdk.products.attributes.AttributeDefinitionDraftBuilder;
import io.sphere.sdk.products.attributes.AttributeDraft;
import io.sphere.sdk.products.attributes.NestedAttributeType;
import io.sphere.sdk.products.attributes.ReferenceAttributeType;
import io.sphere.sdk.products.attributes.SetAttributeType;
import io.sphere.sdk.products.commands.ProductCreateCommand;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.producttypes.ProductTypeDraft;
import io.sphere.sdk.producttypes.ProductTypeDraftBuilder;
import io.sphere.sdk.producttypes.commands.ProductTypeCreateCommand;
import io.sphere.sdk.queries.PagedQueryResult;
import java.time.Clock;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.org.lidalia.slf4jext.Level;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

class ProductSyncWithNestedReferencesIT {

  private static final TestLogger syncerTestLogger = TestLoggerFactory.getTestLogger(Syncer.class);
  private static final TestLogger cliRunnerTestLogger =
      TestLoggerFactory.getTestLogger(CliRunner.class);

  private static final String INNER_PRODUCT_TYPE_KEY = "inner-product-type";
  private static final String MAIN_PRODUCT_TYPE_KEY = "sample-product-type";
  private static final String CATEGORY_KEY = "category-key";
  private static final String MAIN_PRODUCT_MASTER_VARIANT_KEY = "main-product-master-variant-key";
  private static final String MAIN_PRODUCT_KEY = "product-with-nested";
  private static final String NESTED_ATTRIBUTE_NAME = "nested-attribute";

  @BeforeEach
  void setup() {
    syncerTestLogger.clearAll();
    cliRunnerTestLogger.clearAll();
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
    setupSourceProjectData(createClient(CTP_SOURCE_CLIENT_CONFIG));
  }

  static void setupSourceProjectData(@Nonnull final SphereClient sourceProjectClient) {
    final AttributeDefinitionDraft setOfCategoriesAttributeDef =
        AttributeDefinitionDraftBuilder.of(
                SetAttributeType.of(ReferenceAttributeType.ofCategory()),
                "categories",
                ofEnglish("categories"),
                false)
            .searchable(false)
            .attributeConstraint(AttributeConstraint.NONE)
            .build();

    final AttributeDefinitionDraft setOfProductTypeAttributeDef =
        AttributeDefinitionDraftBuilder.of(
                SetAttributeType.of(ReferenceAttributeType.ofProductType()),
                "productTypes",
                ofEnglish("productTypes"),
                false)
            .searchable(false)
            .attributeConstraint(AttributeConstraint.NONE)
            .build();

    final AttributeDefinitionDraft setOfCustomObjectAttributeDef =
        AttributeDefinitionDraftBuilder.of(
                SetAttributeType.of(ReferenceAttributeType.of(CustomObject.referenceTypeId())),
                "customObjects",
                ofEnglish("customObjects"),
                false)
            .searchable(false)
            .attributeConstraint(AttributeConstraint.NONE)
            .build();

    final ProductTypeDraft nestedProductTypeDraft =
        ProductTypeDraftBuilder.of(
                INNER_PRODUCT_TYPE_KEY,
                INNER_PRODUCT_TYPE_KEY,
                "an inner productType for t-shirts",
                emptyList())
            .attributes(
                asList(
                    setOfCategoriesAttributeDef,
                    setOfProductTypeAttributeDef,
                    setOfCustomObjectAttributeDef))
            .build();

    final ProductType innerProductType =
        sourceProjectClient
            .execute(ProductTypeCreateCommand.of(nestedProductTypeDraft))
            .toCompletableFuture()
            .join();

    final AttributeDefinitionDraft nestedAttribute =
        AttributeDefinitionDraftBuilder.of(
                SetAttributeType.of(NestedAttributeType.of(innerProductType)),
                NESTED_ATTRIBUTE_NAME,
                ofEnglish("nested attribute"),
                false)
            .searchable(false)
            .attributeConstraint(AttributeConstraint.NONE)
            .build();

    final ProductTypeDraft productTypeDraft =
        ProductTypeDraftBuilder.of(
                MAIN_PRODUCT_TYPE_KEY,
                MAIN_PRODUCT_TYPE_KEY,
                "a productType for t-shirts",
                emptyList())
            .attributes(singletonList(nestedAttribute))
            .build();

    final ProductType mainProductType =
        sourceProjectClient
            .execute(ProductTypeCreateCommand.of(productTypeDraft))
            .toCompletableFuture()
            .join();

    final CategoryDraft categoryDraft =
        CategoryDraftBuilder.of(ofEnglish("t-shirts"), ofEnglish("t-shirts"))
            .key(CATEGORY_KEY)
            .build();

    final Category category =
        sourceProjectClient
            .execute(CategoryCreateCommand.of(categoryDraft))
            .toCompletableFuture()
            .join();

    final ObjectNode customObjectValue =
        JsonNodeFactory.instance.objectNode().put("field", "value1");

    final CustomObjectDraft<JsonNode> customObjectDraft =
        CustomObjectDraft.ofUnversionedUpsert("container", "key1", customObjectValue);

    final ObjectNode customObjectValue2 =
        JsonNodeFactory.instance.objectNode().put("field", "value2");

    final CustomObjectDraft<JsonNode> customObjectDraft2 =
        CustomObjectDraft.ofUnversionedUpsert("container", "key2", customObjectValue2);

    final CustomObject<JsonNode> customObject1 =
        sourceProjectClient
            .execute(CustomObjectUpsertCommand.of(customObjectDraft))
            .toCompletableFuture()
            .join();

    final CustomObject<JsonNode> customObject2 =
        sourceProjectClient
            .execute(CustomObjectUpsertCommand.of(customObjectDraft2))
            .toCompletableFuture()
            .join();

    final ArrayNode setAttributeValue = JsonNodeFactory.instance.arrayNode();

    final ArrayNode nestedAttributeValue = JsonNodeFactory.instance.arrayNode();

    final ArrayNode categoriesReferencesAttributeValue = JsonNodeFactory.instance.arrayNode();
    categoriesReferencesAttributeValue.add(
        createReferenceObject(Category.referenceTypeId(), category.getId()));

    final ArrayNode productTypesReferencesAttributeValue = JsonNodeFactory.instance.arrayNode();
    productTypesReferencesAttributeValue.add(
        createReferenceObject(ProductType.referenceTypeId(), mainProductType.getId()));

    final ArrayNode customObjectReferencesAttributeValue = JsonNodeFactory.instance.arrayNode();
    customObjectReferencesAttributeValue.add(
        createReferenceObject(CustomObject.referenceTypeId(), customObject1.getId()));
    customObjectReferencesAttributeValue.add(
        createReferenceObject(CustomObject.referenceTypeId(), customObject2.getId()));

    nestedAttributeValue.add(
        createAttributeObject(
            setOfCategoriesAttributeDef.getName(), categoriesReferencesAttributeValue));
    nestedAttributeValue.add(
        createAttributeObject(
            setOfProductTypeAttributeDef.getName(), productTypesReferencesAttributeValue));
    nestedAttributeValue.add(
        createAttributeObject(
            setOfCustomObjectAttributeDef.getName(), customObjectReferencesAttributeValue));

    setAttributeValue.add(nestedAttributeValue);

    final AttributeDraft setOfNestedAttribute =
        AttributeDraft.of(nestedAttribute.getName(), setAttributeValue);

    final ProductVariantDraft masterVariant =
        ProductVariantDraftBuilder.of()
            .key(MAIN_PRODUCT_MASTER_VARIANT_KEY)
            .sku(MAIN_PRODUCT_MASTER_VARIANT_KEY)
            .attributes(setOfNestedAttribute)
            .build();

    final ProductDraft productDraftWithNestedAttribute =
        ProductDraftBuilder.of(
                mainProductType,
                ofEnglish(MAIN_PRODUCT_KEY),
                ofEnglish(MAIN_PRODUCT_KEY),
                masterVariant)
            .key(MAIN_PRODUCT_KEY)
            .build();

    sourceProjectClient
        .execute(ProductCreateCommand.of(productDraftWithNestedAttribute))
        .toCompletableFuture()
        .join();
  }

  @AfterAll
  static void tearDownSuite() {
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
  }

  @Test
  void run_WithSyncAsArgumentWithAllArgAsFullSync_ShouldExecuteAllSyncers() {
    // preparation
    try (final SphereClient targetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
      try (final SphereClient sourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {

        final SyncerFactory syncerFactory =
            SyncerFactory.of(() -> sourceClient, () -> targetClient, Clock.systemDefaultZone());

        // test
        CliRunner.of().run(new String[] {"-s", "all", "-r", "runnerName", "-f"}, syncerFactory);
      }
    }

    // create clients again (for assertions and cleanup), since the run method closes the clients
    // after execution is done.
    try (final SphereClient postTargetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
      // assertions

      assertThat(cliRunnerTestLogger.getAllLoggingEvents())
          .allMatch(loggingEvent -> !Level.ERROR.equals(loggingEvent.getLevel()));

      assertThat(syncerTestLogger.getAllLoggingEvents())
          .allMatch(loggingEvent -> !Level.ERROR.equals(loggingEvent.getLevel()));

      assertTypeSyncerLoggingEvents(syncerTestLogger, 0);
      assertProductTypeSyncerLoggingEvents(syncerTestLogger, 2);
      assertTaxCategorySyncerLoggingEvents(syncerTestLogger, 0);
      assertCategorySyncerLoggingEvents(syncerTestLogger, 1);
      assertProductSyncerLoggingEvents(syncerTestLogger, 1);
      assertInventoryEntrySyncerLoggingEvents(syncerTestLogger, 0);
      assertCartDiscountSyncerLoggingEvents(syncerTestLogger, 0);
      assertStateSyncerLoggingEvents(
          syncerTestLogger, 1); // 1 state is built-in and it will always be processed
      assertCustomObjectSyncerLoggingEvents(syncerTestLogger, 2);
      assertCustomerSyncerLoggingEvents(syncerTestLogger, 0);
      assertShoppingListSyncerLoggingEvents(syncerTestLogger, 0);
      // Every sync module is expected to have 2 logs (start and stats summary)
      assertThat(syncerTestLogger.getAllLoggingEvents()).hasSize(22);

      assertAllResourcesAreSyncedToTarget(postTargetClient);
    }
  }

  private static void assertAllResourcesAreSyncedToTarget(
      @Nonnull final SphereClient targetClient) {

    assertProductTypeExists(targetClient, INNER_PRODUCT_TYPE_KEY);
    final ProductType productType = assertProductTypeExists(targetClient, MAIN_PRODUCT_TYPE_KEY);
    final Category category = assertCategoryExists(targetClient, CATEGORY_KEY);

    final PagedQueryResult<Product> productQueryResult =
        targetClient.execute(ProductQuery.of()).toCompletableFuture().join();

    assertThat(productQueryResult.getResults())
        .hasSize(1)
        .singleElement()
        .satisfies(
            product -> {
              assertThat(product.getKey()).isEqualTo(MAIN_PRODUCT_KEY);
              final ProductVariant stagedMasterVariant =
                  product.getMasterData().getStaged().getMasterVariant();
              assertThat(stagedMasterVariant.getKey()).isEqualTo(MAIN_PRODUCT_MASTER_VARIANT_KEY);
              assertThat(stagedMasterVariant.getAttributes()).hasSize(1);
              assertThat(stagedMasterVariant.getAttribute(NESTED_ATTRIBUTE_NAME))
                  .satisfies(
                      attribute -> {
                        final JsonNode attributeValue = attribute.getValueAsJsonNode();
                        assertThat(attributeValue).isExactlyInstanceOf(ArrayNode.class);
                        final ArrayNode attributeValueAsArray = (ArrayNode) attributeValue;
                        assertThat(attributeValueAsArray).hasSize(1);

                        final JsonNode nestedAttributeElement = attributeValueAsArray.get(0);
                        assertThat(nestedAttributeElement).isExactlyInstanceOf(ArrayNode.class);
                        final ArrayNode nestedTypeAttributes = (ArrayNode) nestedAttributeElement;
                        assertThat(nestedTypeAttributes).hasSize(3);

                        assertThat(nestedTypeAttributes.get(0).get("name").asText())
                            .isEqualTo("categories");
                        assertThat(nestedTypeAttributes.get(0).get("value"))
                            .isExactlyInstanceOf(ArrayNode.class);
                        final ArrayNode categoryReferences =
                            (ArrayNode) (nestedTypeAttributes.get(0).get("value"));
                        assertThat(categoryReferences)
                            .singleElement()
                            .satisfies(
                                categoryReference ->
                                    assertThat(categoryReference.get("id").asText())
                                        .isEqualTo(category.getId()));

                        assertThat(nestedTypeAttributes.get(1).get("name").asText())
                            .isEqualTo("productTypes");
                        assertThat(nestedTypeAttributes.get(1).get("value"))
                            .isExactlyInstanceOf(ArrayNode.class);
                        final ArrayNode productTypeReferences =
                            (ArrayNode) (nestedTypeAttributes.get(1).get("value"));
                        assertThat(productTypeReferences)
                            .singleElement()
                            .satisfies(
                                productTypeReference ->
                                    assertThat(productTypeReference.get("id").asText())
                                        .isEqualTo(productType.getId()));

                        assertThat(nestedTypeAttributes.get(2).get("name").asText())
                            .isEqualTo("customObjects");
                        assertThat(nestedTypeAttributes.get(2).get("value"))
                            .isExactlyInstanceOf(ArrayNode.class);
                        final ArrayNode customObjectReferences =
                            (ArrayNode) (nestedTypeAttributes.get(2).get("value"));
                        assertThat(customObjectReferences).hasSize(2);
                      });
            });
  }
}
