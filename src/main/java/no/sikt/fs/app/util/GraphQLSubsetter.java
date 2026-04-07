package no.sikt.fs.app.util;

import com.apollographql.federation.graphqljava._FieldSet;
import com.apollographql.federation.graphqljava.link__Import;
import graphql.analysis.QueryTraverser;
import graphql.analysis.QueryVisitorFieldEnvironment;
import graphql.analysis.QueryVisitorStub;
import graphql.language.Description;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.*;

import java.util.*;

public class GraphQLSubsetter {
    private static final String FEDERATION_FIELDSET = "federation__FieldSet";
    private static final Set<String> BUILT_IN_SCALARS = Set.of("String", "Int", "Float", "Boolean", "ID");
    private final TypeDefinitionRegistry typeDefinitionRegistry;
    private final Map<String, GraphQLScalarType> federationScalars = Map.of(
        FEDERATION_FIELDSET, _FieldSet.type.transform(it -> it.name(FEDERATION_FIELDSET)),
        link__Import.type.getName(), link__Import.type
    );

    private final RuntimeWiring mockedWiring = RuntimeWiring
        .newRuntimeWiring()
        .wiringFactory(new MockedWiringFactory() {
            @Override
            public GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
                var name = environment.getScalarTypeDefinition().getName();
                var type = federationScalars.get(name);
                if (type != null) {
                    return type;
                }

                return super.getScalar(environment);
            }


        }).build();

    public GraphQLSubsetter(TypeDefinitionRegistry typeDefinitionRegistry) {
        this.typeDefinitionRegistry = typeDefinitionRegistry;
    }

    public String generateSubsetSdl(String query) {
        Document queryDocument = Parser.parse(query);
        TypeDefinitionRegistry subsetRegistry = generateSubsetRegistry(queryDocument);
        GraphQLSchema subsetSchema = new SchemaGenerator().makeExecutableSchema(subsetRegistry, mockedWiring);
        return new SchemaPrinter(SchemaPrinter.Options.defaultOptions().includeDirectives(false)).print(subsetSchema);
    }

    protected TypeDefinitionRegistry generateSubsetRegistry(Document queryDocument) {
        Objects.requireNonNull(queryDocument, "queryDocument must not be null");

        Map<String, Set<String>> fieldsByParent = getTouchedFieldsByParent(queryDocument);
        return createPrunedTypeDefinitionRegistry(fieldsByParent);
    }

    private Map<String, Set<String>> getTouchedFieldsByParent(Document queryDocument) {
        var schema = new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, mockedWiring);
        Map<String, Set<String>> fieldsByParent = new LinkedHashMap<>();

        QueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(queryDocument)
            .variables(Collections.emptyMap())
            .build()
            .visitPreOrder(new QueryVisitorStub() {
                @Override
                public void visitField(QueryVisitorFieldEnvironment env) {
                    GraphQLFieldsContainer parent = env.getFieldsContainer();
                    GraphQLFieldDefinition fieldDef = env.getFieldDefinition();

                    fieldsByParent
                        .computeIfAbsent(parent.getName(), k -> new LinkedHashSet<>())
                        .add(fieldDef.getName());
                }
            });

        return fieldsByParent;
    }

    private TypeDefinitionRegistry createPrunedTypeDefinitionRegistry(Map<String, Set<String>> fieldsByParent) {
        var pruned = new TypeDefinitionRegistry();

        for (var entry : fieldsByParent.entrySet()) {
            String typeName = entry.getKey();
            Set<String> fieldNames = entry.getValue();

            TypeDefinition<?> def = typeDefinitionRegistry.getType(typeName).get();
            if (def instanceof ObjectTypeDefinition obj) {
                List<FieldDefinition> fields = obj.getFieldDefinitions()
                    .stream()
                    .filter(f -> fieldNames.contains(f.getName()))
                    .toList();
                pruned.add(obj.transform(it -> it.fieldDefinitions(fields)));

                var extensions = typeDefinitionRegistry.objectTypeExtensions().get(typeName);
                if (extensions != null) {
                    for (var extension : extensions) {
                        List<FieldDefinition> extendedFields = extension.getFieldDefinitions()
                            .stream()
                            .filter(it -> fieldNames.contains(it.getName()))
                            .toList();

                        if (!extendedFields.isEmpty()) {
                            pruned.add(extension.transformExtension(it -> it.fieldDefinitions(extendedFields)));
                        }
                    }
                }
            } else {
                pruned.add(def);
            }
        }

        // Bug 4: GraphQL spec requires a query type; if only mutations/subscriptions
        // were queried, Query won't be in fieldsByParent — add a minimal stub.
        if (pruned.getType("Query").isEmpty()) {
            var placeholder = FieldDefinition.newFieldDefinition()
                .name("empty")
                .type(TypeName.newTypeName("String").build())
                .description(new Description(
                    "Placeholder field: the GraphQL specification requires a Query type.",
                    null, false))
                .build();
            pruned.add(ObjectTypeDefinition.newObjectTypeDefinition()
                .name("Query")
                .fieldDefinition(placeholder)
                .build());
        }

        // Bugs 1, 2, 3: fixed-point closure — keep adding types referenced by the
        // pruned registry (scalars, input types, unions, interfaces, enums) until stable.
        boolean added = true;
        while (added) {
            added = false;
            List<?> snapshot = new ArrayList<>(pruned.types().values());
            for (var rawDef : snapshot) {
                TypeDefinition<?> def = (TypeDefinition<?>) rawDef;
                for (String name : referencedTypeNames(def)) {
                    if (BUILT_IN_SCALARS.contains(name) || pruned.getType(name).isPresent()) {
                        continue;
                    }
                    var original = typeDefinitionRegistry.getType(name);
                    if (original.isPresent()) {
                        pruned.add(original.get());
                        added = true;
                    }
                }
            }
        }

        return pruned;
    }

    private Set<String> referencedTypeNames(TypeDefinition<?> def) {
        Set<String> names = new HashSet<>();
        if (def instanceof ObjectTypeDefinition obj) {
            obj.getImplements().forEach(t -> names.add(unwrapTypeName(t)));
            for (var field : obj.getFieldDefinitions()) {
                names.add(unwrapTypeName(field.getType()));
                field.getInputValueDefinitions().forEach(iv -> names.add(unwrapTypeName(iv.getType())));
            }
        } else if (def instanceof InterfaceTypeDefinition iface) {
            iface.getImplements().forEach(t -> names.add(unwrapTypeName(t)));
            for (var field : iface.getFieldDefinitions()) {
                names.add(unwrapTypeName(field.getType()));
                field.getInputValueDefinitions().forEach(iv -> names.add(unwrapTypeName(iv.getType())));
            }
        } else if (def instanceof InputObjectTypeDefinition input) {
            input.getInputValueDefinitions().forEach(iv -> names.add(unwrapTypeName(iv.getType())));
        } else if (def instanceof UnionTypeDefinition union) {
            union.getMemberTypes().forEach(t -> names.add(unwrapTypeName(t)));
        }
        return names;
    }

    private String unwrapTypeName(Type<?> type) {
        if (type instanceof TypeName tn) {
            return tn.getName();
        } else if (type instanceof NonNullType nnt) {
            return unwrapTypeName(nnt.getType());
        } else if (type instanceof ListType lt) {
            return unwrapTypeName(lt.getType());
        }
        throw new IllegalArgumentException("Unknown Type node: " + type.getClass());
    }
}
