package no.sikt.fs.app.util;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class GraphQLSchemaDotGeneratorTest {

    // ── Node presence ─────────────────────────────────────────────────────────

    @Test
    void objectTypesAreRenderedAsNodes() throws IOException {
        var dot = dotFor("simple");
        assertTrue(dot.contains("\"Query\""), "Query node missing");
        assertTrue(dot.contains("\"Fugl\""),  "Fugl node missing");
        assertTrue(dot.contains("\"Fisk\""),  "Fisk node missing");
        assertTrue(dot.contains("\"Frosk\""), "Frosk node missing");
    }

    @Test
    void interfaceTypesAreRenderedAsNodes() throws IOException {
        var dot = dotFor("simple");
        assertTrue(dot.contains("\"Node\""),    "Node interface node missing");
        assertTrue(dot.contains("interface"),   "interface kind label missing");
        assertTrue(dot.contains("&lt;&lt;interface&gt;&gt;"), "<<interface>> label missing");
    }

    @Test
    void unionTypesAreRenderedAsNodes() throws IOException {
        var dot = dotFor("union");
        assertTrue(dot.contains("\"Pet\""),   "Pet union node missing");
        assertTrue(dot.contains("union"),     "union kind label missing");
        assertTrue(dot.contains("possible types"), "possible types section missing");
    }

    // ── Node exclusions ───────────────────────────────────────────────────────

    @Test
    void builtInScalarsAreExcludedAsNodes() throws IOException {
        var dot = dotFor("simple");
        // These must not appear as top-level node identifiers
        assertFalse(dot.contains("\"String\""),  "String should not be a node");
        assertFalse(dot.contains("\"ID\""),      "ID should not be a node");
        assertFalse(dot.contains("\"Boolean\""), "Boolean should not be a node");
    }

    @Test
    void inputTypesAreExcludedAsNodes() throws IOException {
        var dot = dotFor("simple");
        assertFalse(dot.contains("\"FroskQuery\""), "Input type FroskQuery should not be a node");
    }

    @Test
    void introspectionTypesAreExcluded() throws IOException {
        var dot = dotFor("simple");
        assertFalse(dot.contains("\"__Schema\""), "__Schema should not be a node");
        assertFalse(dot.contains("\"__Type\""),   "__Type should not be a node");
    }

    @Test
    void customScalarsAreExcludedAsNodes() throws IOException {
        var dot = dotFor("scalar");
        assertFalse(dot.contains("\"DateTime\""), "Custom scalar DateTime should not be a node");
        assertFalse(dot.contains("\"UUID\""),     "Custom scalar UUID should not be a node");
    }

    // ── Field rows in nodes ───────────────────────────────────────────────────

    @Test
    void leafFieldsAppearInsideNodeWithoutEdgePort() throws IOException {
        var dot = dotFor("simple");
        // "navn" is a String field on Fugl — shown in the label but no PORT and no edge
        assertTrue(dot.contains("navn"),                       "leaf field 'navn' should appear in label");
        assertFalse(dot.contains("PORT=\"navn\""),             "leaf field should not have a PORT");
        assertFalse(dot.contains("\"Fugl\":\"navn\" ->"),      "no edge for leaf field");
    }

    @Test
    void compositeFieldsHavePortAttributes() throws IOException {
        var dot = dotFor("simple");
        assertTrue(dot.contains("PORT=\"node\""), "composite field 'node' should have PORT");
        assertTrue(dot.contains("PORT=\"fisk\""), "composite field 'fisk' should have PORT");
    }

    // ── Edges ─────────────────────────────────────────────────────────────────

    @Test
    void solidEdgesConnectFieldsToCompositeTypes() throws IOException {
        var dot = dotFor("simple");
        assertTrue(dot.contains("\"Query\":\"node\" -> \"Node\""),   "edge Query.node -> Node missing");
        assertTrue(dot.contains("\"Query\":\"fisk\" -> \"Fisk\""),   "edge Query.fisk -> Fisk missing");
        assertTrue(dot.contains("\"Query\":\"frosk\" -> \"Frosk\""), "edge Query.frosk -> Frosk missing");
    }

    @Test
    void dottedEdgesForInterfaceImplementations() throws IOException {
        var dot = dotFor("simple");
        // Node interface → Fugl implementor
        assertTrue(dot.contains("\"Node\":\"Fugl\" -> \"Fugl\" [style=dotted]"),
            "dotted edge from Node to its implementation Fugl missing");
    }

    @Test
    void dashedEdgesForUnionMembers() throws IOException {
        var dot = dotFor("union");
        assertTrue(dot.contains("\"Pet\":\"Cat\" -> \"Cat\" [style=dashed]"),
            "dashed edge Pet -> Cat missing");
        assertTrue(dot.contains("\"Pet\":\"Dog\" -> \"Dog\" [style=dashed]"),
            "dashed edge Pet -> Dog missing");
    }

    @Test
    void noEdgeCreatedForScalarReturnType() throws IOException {
        // Frosk.number returns BigDecimal (custom scalar) — no edge
        var dot = dotFor("simple");
        assertFalse(dot.contains("\"Frosk\":\"number\" ->"), "no edge for scalar field");
    }

    // ── SVG generation ────────────────────────────────────────────────────────

    @Test
    void generateSvgProducesWellFormedSvg() throws IOException, InterruptedException {
        var schemaPath = Paths.get("src", "test", "resources", "simple", "schema.graphql");
        TypeDefinitionRegistry registry = new SchemaParser().parse(Files.readString(schemaPath));
        var svg = new GraphQLSchemaDotGenerator(registry).generateSvg();

        assertTrue(svg.contains("<svg"),           "output should be SVG");
        assertTrue(svg.contains("</svg>"),         "output should be closed SVG");
        assertTrue(svg.contains("Query"),          "SVG should contain type name Query");
        assertTrue(svg.contains("Fugl"),           "SVG should contain type name Fugl");
    }

    // ── Graph structure ───────────────────────────────────────────────────────

    @Test
    void outputIsValidDotDigraph() throws IOException {
        var dot = dotFor("simple");
        assertTrue(dot.startsWith("digraph {"), "should start with digraph");
        assertTrue(dot.contains("rankdir=LR"),  "should use left-to-right layout");
        assertTrue(dot.contains("shape=plaintext"), "nodes should use plaintext shape");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String dotFor(String testCase) throws IOException {
        var schemaPath = Paths.get("src", "test", "resources", testCase, "schema.graphql");
        TypeDefinitionRegistry registry = new SchemaParser().parse(Files.readString(schemaPath));
        return new GraphQLSchemaDotGenerator(registry).generateDot();
    }
}
