# ADR-0005: Lombok for Boilerplate Reduction in Entity Classes

**Date**: 2026-04-27  
**Status**: accepted  
**Deciders**: Architecture team

## Context

Trove's entity classes require boilerplate code: getters, setters, constructors, equals, hashCode, and toString methods. Writing these by hand is tedious, error-prone, and clutters source files. Lombok is a popular annotation processor that generates this boilerplate at compile time, keeping source files focused on business logic. The trade-off is that Lombok adds a compiler dependency and makes debugging slightly harder (stack traces may reference generated code).

## Decision

Use Lombok annotations (`@Data`, `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@EqualsAndHashCode`, `@ToString`) on all entity classes to eliminate boilerplate. Exclude sensitive fields from Lombok's generated methods using `@ToString.Exclude` and `@EqualsAndHashCode.Exclude` (e.g., `configEncrypted` field on `ConnectedAccount` must never be logged or compared). Lombok's compile-time code generation integrates seamlessly with Spring Boot and Jakarta persistence providers.

## Alternatives Considered

### Alternative 1: Manual Implementation of Getters/Setters/Equals
- **Pros**: Full control over generated code; no compiler plugin dependency; easier to debug
- **Cons**: Hundreds of lines of boilerplate per entity; high maintenance burden; error-prone (equals/hashCode must be consistent)
- **Why not**: Reduces readability of entity classes; wastes developer time on repetitive code

### Alternative 2: Jakarta Records (Java 16+)
- **Pros**: Built-in immutable record syntax; no external dependency; native JVM support
- **Cons**: Records are immutable (cannot use setters); requires Java 16+; records don't support inheritance well
- **Why not**: Trove's entities are mutable (JPA requires setters for lazy loading); records are better suited to DTOs

### Alternative 3: IDE-Generated Boilerplate
- **Pros**: Standard IDE feature; no external dependency; full visibility of generated code
- **Cons**: Generated code clutters source files; must regenerate when fields change; inconsistent across team
- **Why not**: Poor maintainability; generated code in source files makes classes hard to read

## Consequences

### Positive
- Entity classes are concise and focused on business logic
- Getters, setters, equals, hashCode, toString are generated consistently
- Sensitive fields can be excluded from toString/equals using `@ToString.Exclude`
- Standard annotation syntax recognized by any Spring Boot developer
- Reduces bugs related to incorrect equals/hashCode implementations
- Compile-time code generation adds negligible overhead to build time

### Negative
- Requires Lombok annotation processor in the build pipeline
- Stack traces reference generated method names (harder to debug)
- Intellij IDE integration requires Lombok plugin
- New team members unfamiliar with Lombok may be confused by missing method definitions in source

### Risks
- **IDE compatibility**: Lombok integration varies across IDE versions. Mitigated by requiring IDE Lombok plugin in developer onboarding
- **Reflection-based frameworks**: Frameworks that introspect class structure may not recognize Lombok-generated methods. Mitigated by ensuring IDE and build pipeline both include Lombok
- **Debugging generated code**: Stack traces may point to synthetic method names. Mitigated by understanding that Lombok generates standard patterns
