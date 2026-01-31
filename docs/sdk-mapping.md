# RunAnywhere SDK Method Mapping

## Core SDK Methods

| Method | Description | Platform |
|--------|-------------|----------|
| `initialize(config)` | Initialize the SDK with configuration | All |
| `startAgent(params)` | Start an agent session | All |
| `stopAgent()` | Stop the currently running agent | All |
| `sendMessage(message)` | Send a message to the agent | All |
| `onResponse(callback)` | Register a callback for agent responses | All |
| `getStatus()` | Get the current agent status | All |

## Configuration

```typescript
interface RunAnywhereConfig {
  apiKey: string;
  endpoint: string;
  model?: string;
  timeout?: number;
}
```

## Agent Parameters

```typescript
interface AgentParams {
  prompt: string;
  tools?: string[];
  maxTurns?: number;
  streaming?: boolean;
}
```

## Response Types

```typescript
interface AgentResponse {
  id: string;
  type: 'text' | 'tool_use' | 'error' | 'done';
  content: string;
  metadata?: Record<string, unknown>;
}
```
