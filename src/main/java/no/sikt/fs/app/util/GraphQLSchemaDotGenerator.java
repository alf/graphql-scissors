package no.sikt.fs.app.util;

import com.apollographql.federation.graphqljava._FieldSet;
import com.apollographql.federation.graphqljava.link__Import;
import graphql.schema.*;
import graphql.schema.idl.*;

import java.util.*;

/**
 * Generates a Graphviz DOT file that visualises a GraphQL schema.
 *
 * <p>Design follows GraphQL Voyager's conventions:
 * <ul>
 *   <li>Only composite types (Object, Interface, Union) become graph nodes.
 *       Scalars, enums and input types are excluded as nodes but appear as
 *       field-type labels inside the relevant object/interface node.</li>
 *   <li>Solid arrows  — a field whose return type is another composite type.</li>
 *   <li>Dashed arrows — union → possible member type.</li>
 *   <li>Dotted arrows — interface → implementing object type.</li>
 * </ul>
 */
public class GraphQLSchemaDotGenerator {

    private static final Set<String> BUILT_IN_SCALARS =
            Set.of("String", "Int", "Float", "Boolean", "ID");

    private static final String FEDERATION_FIELDSET = "federation__FieldSet";

    // Header background colours per type kind
    private static final String COLOR_OBJECT    = "#DDEEBB";
    private static final String COLOR_INTERFACE = "#BBD8EE";
    private static final String COLOR_UNION     = "#EEEEBB";
    private static final String COLOR_SECTION   = "#F0F0F0";

    private final GraphQLSchema schema;

    public GraphQLSchemaDotGenerator(TypeDefinitionRegistry typeDefinitionRegistry) {
        var federationScalars = Map.of(
            FEDERATION_FIELDSET, _FieldSet.type.transform(b -> b.name(FEDERATION_FIELDSET)),
            link__Import.type.getName(), link__Import.type
        );
        var wiring = RuntimeWiring.newRuntimeWiring()
            .wiringFactory(new MockedWiringFactory() {
                @Override
                public GraphQLScalarType getScalar(ScalarWiringEnvironment env) {
                    var t = federationScalars.get(env.getScalarTypeDefinition().getName());
                    return t != null ? t : super.getScalar(env);
                }
            }).build();
        this.schema = new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, wiring);
    }

    public String generateDot() {
        var sb = new StringBuilder();
        sb.append("digraph {\n");
        sb.append("  graph [rankdir=LR ranksep=2]\n");
        sb.append("  node [fontname=Helvetica fontsize=14 shape=plaintext]\n");
        sb.append("  edge [fontname=Helvetica fontsize=12]\n\n");

        var types = schema.getTypeMap().values().stream()
            .filter(this::isVisible)
            .sorted(Comparator.comparing(GraphQLNamedType::getName))
            .toList();

        for (var type : types) {
            sb.append(renderNode(type));
        }
        sb.append("\n");
        for (var type : types) {
            renderEdges(type, sb);
        }
        sb.append("}\n");
        return sb.toString();
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    private boolean isVisible(GraphQLNamedType type) {
        String name = type.getName();
        return !name.startsWith("__")
            && !BUILT_IN_SCALARS.contains(name)
            && (type instanceof GraphQLObjectType
                || type instanceof GraphQLInterfaceType
                || type instanceof GraphQLUnionType);
    }

    // ── Nodes ─────────────────────────────────────────────────────────────────

    private String renderNode(GraphQLNamedType type) {
        if (type instanceof GraphQLObjectType obj)    return objectNode(obj);
        if (type instanceof GraphQLInterfaceType ifc) return interfaceNode(ifc);
        if (type instanceof GraphQLUnionType union)   return unionNode(union);
        throw new IllegalArgumentException("Unexpected type: " + type);
    }

    private String objectNode(GraphQLObjectType type) {
        var rows = new StringBuilder(headerRow(type.getName(), null, COLOR_OBJECT));
        type.getFieldDefinitions().forEach(f -> rows.append(fieldRow(f)));
        return nodeStatement(type.getName(), rows.toString());
    }

    private String interfaceNode(GraphQLInterfaceType type) {
        var rows = new StringBuilder(headerRow(type.getName(), "interface", COLOR_INTERFACE));
        type.getFieldDefinitions().forEach(f -> rows.append(fieldRow(f)));
        var impls = implementationsOf(type);
        if (!impls.isEmpty()) {
            rows.append(sectionRow("implementations"));
            impls.forEach(impl -> rows.append(portRow(impl.getName())));
        }
        return nodeStatement(type.getName(), rows.toString());
    }

    private String unionNode(GraphQLUnionType type) {
        var rows = new StringBuilder(headerRow(type.getName(), "union", COLOR_UNION));
        rows.append(sectionRow("possible types"));
        type.getTypes().stream()
            .sorted(Comparator.comparing(t -> t.getName()))
            .forEach(m -> rows.append(portRow(m.getName())));
        return nodeStatement(type.getName(), rows.toString());
    }

    // ── Edges ─────────────────────────────────────────────────────────────────

    private void renderEdges(GraphQLNamedType type, StringBuilder sb) {
        if (type instanceof GraphQLObjectType obj) {
            obj.getFieldDefinitions().forEach(f -> {
                if (isVisible(unwrap(f.getType()))) {
                    sb.append("  ").append(q(obj.getName())).append(":").append(q(f.getName()))
                      .append(" -> ").append(q(unwrap(f.getType()).getName())).append("\n");
                }
            });
        } else if (type instanceof GraphQLInterfaceType ifc) {
            ifc.getFieldDefinitions().forEach(f -> {
                if (isVisible(unwrap(f.getType()))) {
                    sb.append("  ").append(q(ifc.getName())).append(":").append(q(f.getName()))
                      .append(" -> ").append(q(unwrap(f.getType()).getName())).append("\n");
                }
            });
            implementationsOf(ifc).forEach(impl ->
                sb.append("  ").append(q(ifc.getName())).append(":").append(q(impl.getName()))
                  .append(" -> ").append(q(impl.getName())).append(" [style=dotted]\n")
            );
        } else if (type instanceof GraphQLUnionType union) {
            union.getTypes().stream()
                .sorted(Comparator.comparing(t -> t.getName()))
                .forEach(m ->
                    sb.append("  ").append(q(union.getName())).append(":").append(q(m.getName()))
                      .append(" -> ").append(q(m.getName())).append(" [style=dashed]\n")
                );
        }
    }

    // ── HTML label helpers ────────────────────────────────────────────────────

    private String nodeStatement(String name, String rows) {
        return "  " + q(name) + " [label=<\n"
            + "    <TABLE ALIGN=\"LEFT\" BORDER=\"0\" CELLBORDER=\"1\" CELLSPACING=\"0\" CELLPADDING=\"5\">\n"
            + rows
            + "    </TABLE>\n  >]\n";
    }

    private String headerRow(String name, String kind, String color) {
        String kindHtml = kind == null ? "" :
            "\n      <BR/><FONT POINT-SIZE=\"11\" COLOR=\"#666666\">"
            + "&lt;&lt;" + h(kind) + "&gt;&gt;</FONT>";
        return "      <TR><TD COLSPAN=\"2\" CELLPADDING=\"6\" BGCOLOR=\"" + color + "\">"
            + "<FONT POINT-SIZE=\"16\"><B>" + h(name) + "</B></FONT>"
            + kindHtml + "</TD></TR>\n";
    }

    /** One row per field. Composite-typed fields get a PORT for edge anchoring;
     *  leaf fields (scalars/enums) are shown greyed out with no port. */
    private String fieldRow(GraphQLFieldDefinition field) {
        boolean edge = isVisible(unwrap(field.getType()));
        String nameColor = edge ? "#222222" : "#999999";
        String typeColor = edge ? "#555555" : "#BBBBBB";
        String portAttr  = edge ? " PORT=\"" + h(field.getName()) + "\"" : "";
        return "      <TR>"
            + "<TD ALIGN=\"LEFT\"" + portAttr + ">"
            +   "<FONT COLOR=\"" + nameColor + "\">" + h(field.getName()) + "</FONT></TD>"
            + "<TD ALIGN=\"RIGHT\">"
            +   "<FONT COLOR=\"" + typeColor + "\">" + h(typeStr(field.getType())) + "</FONT></TD>"
            + "</TR>\n";
    }

    private String sectionRow(String title) {
        return "      <TR><TD COLSPAN=\"2\" ALIGN=\"LEFT\" BGCOLOR=\"" + COLOR_SECTION + "\">"
            + "<FONT POINT-SIZE=\"10\" COLOR=\"#777777\">" + h(title) + "</FONT></TD></TR>\n";
    }

    private String portRow(String name) {
        return "      <TR><TD COLSPAN=\"2\" PORT=\"" + h(name) + "\" ALIGN=\"LEFT\">"
            + h(name) + "</TD></TR>\n";
    }

    // ── Type utilities ────────────────────────────────────────────────────────

    private String typeStr(GraphQLType type) {
        if (type instanceof GraphQLNonNull nn) return typeStr(nn.getWrappedType()) + "!";
        if (type instanceof GraphQLList   lt) return "[" + typeStr(lt.getWrappedType()) + "]";
        if (type instanceof GraphQLNamedType  n) return n.getName();
        return type.toString();
    }

    private GraphQLNamedType unwrap(GraphQLType type) {
        if (type instanceof GraphQLNonNull nn) return unwrap(nn.getWrappedType());
        if (type instanceof GraphQLList   lt) return unwrap(lt.getWrappedType());
        if (type instanceof GraphQLNamedType  n) return n;
        throw new IllegalArgumentException("Cannot unwrap type: " + type);
    }

    private List<GraphQLObjectType> implementationsOf(GraphQLInterfaceType ifc) {
        return schema.getTypeMap().values().stream()
            .filter(t -> t instanceof GraphQLObjectType)
            .map(t -> (GraphQLObjectType) t)
            .filter(obj -> obj.getInterfaces().stream().anyMatch(i -> i.getName().equals(ifc.getName())))
            .sorted(Comparator.comparing(GraphQLObjectType::getName))
            .toList();
    }

    /** HTML-escapes text for use inside HTML-like labels. */
    private static String h(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Wraps a name in DOT double-quoted identifier syntax. */
    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
