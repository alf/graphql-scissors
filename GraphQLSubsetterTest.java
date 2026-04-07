package no.sikt.fs.app.util;

import graphql.language.AstPrinter;
import graphql.language.AstSorter;
import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphQLSubsetterTest {
    @MethodSource("queryFiles")
    @ParameterizedTest(name="{0}")
    public void testSubsetSchemaIncludesMentionedFields_extended(String name, Path schema, Path query, Path expected) throws Exception {
        TypeDefinitionRegistry tdr = getTypeDefinitionRegistry(schema);
        String queryString = Files.readString(query);
        String expectedString = Files.readString(expected);

        String actual = new GraphQLSubsetter(tdr).generateSubsetSdl(queryString);
        assertEquals(normalize(expectedString), normalize(actual));
    }

    protected TypeDefinitionRegistry getTypeDefinitionRegistry(Path schema) throws IOException {
        var schemaString = Files.readString(schema);
        return new SchemaParser().parse(schemaString);
    }

    protected String normalize(String sdl) {
        Document doc = Parser.parse(sdl);
        return AstPrinter.printAst(new AstSorter().sort(doc));
    }

    private static Stream<Arguments> queryFiles() throws IOException {
        return Files.walk(Paths.get("src", "test", "resources"))
            .filter(it -> it.toString().endsWith(".query.graphql"))
            .map(GraphQLSubsetterTest::queryfileToArguments);
    }

    private static Arguments queryfileToArguments(Path query) {
        String name = query.getFileName().toString().replace(".query.graphql", "");
        Path schema = query.resolveSibling("schema.graphql");
        Path expected = query.resolveSibling(name + ".expected.graphql");
        return Arguments.of(name, schema, query, expected);
    }
}
