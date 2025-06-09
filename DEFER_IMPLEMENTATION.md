# GraphQL @defer Directive Implementation

This document describes the implementation of the GraphQL `@defer` directive in Lacinia.

## Overview

The `@defer` directive allows clients to defer the execution of certain fields, enabling faster initial response times by returning immediately available data first and deferring slower fields for later delivery.

## Implementation Details

### 1. Directive Definition

Added `@defer` to the built-in directives in `src/com/walmartlabs/lacinia/parser.clj`:

```clojure
:defer {:args label-arg
        :effector (fn [node arguments]
                    (assoc node :deferred? true
                                :defer-label (:label arguments)))}
```

The directive accepts an optional `label` argument of type `String` to identify deferred fragments.

### 2. Execution Context Enhancement

Modified `ExecutionContext` record to include a `*deferred-selections` atom for collecting deferred field selections during execution.

### 3. Selection Processing

Updated `apply-selection` function to:
- Check for `:deferred?` flag on selections
- Collect deferred selections in the execution context
- Return a placeholder value (`:com.walmartlabs.lacinia.executor/deferred`) for deferred fields

### 4. Deferred Processing

Added `process-deferred-selections` function that:
- Processes collected deferred selections after main execution
- Executes deferred fields normally (without the defer flag)
- Extracts resolved values from ResolverResults
- Returns structured deferred results with path, data, and label

### 5. Result Structure

The execution result now includes:
- `:data` - Main response with deferred fields marked as placeholders
- `:deferred` - Array of deferred results with structure:
  ```clojure
  {:path [:user :email]
   :data "resolved-value"
   :label "optional-label"}
  ```

## Usage Examples

### Basic Usage
```graphql
query {
  user(id: "123") {
    id
    name
    email @defer(label: "userEmail")
  }
}
```

### Nested Deferred Fields
```graphql
query {
  user(id: "123") {
    id
    name
    profile {
      bio
      avatar @defer(label: "userAvatar")
    }
    posts @defer(label: "userPosts") {
      id
      title
    }
  }
}
```

### Without Label
```graphql
query {
  user(id: "123") {
    id
    name
    email @defer
  }
}
```

## Result Format

```clojure
{:data {:user {:id "123"
               :name "John Doe"
               :email :com.walmartlabs.lacinia.executor/deferred}}
 :deferred [{:path [:user :email]
             :data "user@example.com"
             :label "userEmail"}]}
```

## Current Limitations

1. **Synchronous Processing**: Currently processes deferred fields synchronously after main execution
2. **No Streaming**: Does not implement true streaming responses (would require transport layer changes)
3. **Error Handling**: Basic error handling for deferred fields
4. **Fragment Support**: Limited testing with fragments

## Future Enhancements

1. **Asynchronous Processing**: Implement true asynchronous deferred field processing
2. **Streaming Support**: Add support for streaming responses over HTTP/WebSocket
3. **Error Isolation**: Better error handling and isolation for deferred fields
4. **Fragment Support**: Enhanced support for deferred fragments
5. **Conditional Defer**: Support for conditional defer based on variables

## Testing

Comprehensive tests are available in `test/com/walmartlabs/lacinia/defer_test.clj` covering:
- Basic defer functionality
- Nested deferred fields
- Defer without labels
- Integration with existing directive system

## Compatibility

This implementation is backward compatible and does not affect existing functionality. All existing directive tests continue to pass.