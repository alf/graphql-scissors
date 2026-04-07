# graphql-scissors

A Java library that cuts a GraphQL schema down to only the types and fields referenced by a given query.

## What it does

Given a full GraphQL schema and a query document, `GraphQLSubsetter` produces a minimal schema SDL that contains exactly the types and fields needed to execute that query — nothing more.

**Full schema:**
```graphql
type Query {
    node(id: ID): Node
    fisk: Fisk
}

interface Node { id: ID }

type Fugl implements Node {
    id: ID
    navn: String
    extended: String
}

type Fisk { navn: String }
```

**Query:**
```graphql
{
    node(id: "") {
        id
        ... on Fugl { id, extended }
    }
}
```

**Output schema subset:**
```graphql
type Query {
    node(id: ID): Node
}

interface Node { id: ID }

type Fugl implements Node {
    id: ID
    extended: String
}
```

`Fisk` and `Fugl.navn` are not referenced by the query, so they are omitted entirely.

## Usage

```java
TypeDefinitionRegistry schema = new SchemaParser().parse(schemaString);
String subsetSdl = new GraphQLSubsetter(schema).generateSubsetSdl(queryString);
```

The library is schema-first: you bring a parsed `TypeDefinitionRegistry` (from graphql-java's `SchemaParser`) and a query string, and get back a printed SDL string.

Apollo Federation scalars (`_FieldSet`, `link__Import`) are handled automatically.

## Building

```
mvn install
```

## Testing

Tests are parameterized and driven by fixture files in `src/test/resources/`. Each test case is a directory containing:

| File | Purpose |
|---|---|
| `schema.graphql` | The full input schema |
| `<name>.query.graphql` | The query to subset against |
| `<name>.expected.graphql` | The expected output schema |

Run with:

```
mvn test
```
