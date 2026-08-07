import React, {useMemo, useState} from 'react';
import Heading from '@theme/Heading';
import toolsData from '@site/src/data/tools.json';
import type {
  JsonSchemaProperty,
  ToolDoc,
  ToolGroup,
  ToolsDocument,
} from '@site/src/types/tools';
import styles from './styles.module.css';

// Via `unknown` deliberately. TypeScript infers a literal type from the JSON in which every tool
// carries the union of *all* tools' schema keys, most of them `undefined` — which is not
// assignable to Record<string, JsonSchemaProperty>, so a direct cast is rejected. What this costs
// is the compiler checking the data against the interface; what it keeps is every consumer below
// being fully typed. The data's actual shape is guaranteed by the generator emitting
// `Tool.toJson()` and by CI diffing the regenerated file.
const data = toolsData as unknown as ToolsDocument;

/**
 * Renders the generated tool reference.
 *
 * Everything here comes from `src/data/tools.json`, which the Gradle generator produces from the
 * module's own `Tool` declarations — so a description shown here is the description the model
 * receives, and the read-only/destructive badges come from the same annotations that decide which
 * endpoint a tool is served on. Nothing about a tool is maintained by hand.
 */
export default function ToolReference({
  scope,
  group,
}: {
  scope: string;
  group?: string;
}): React.JSX.Element {
  const [filter, setFilter] = useState('');

  const scopeData = data.scopes.find((s) => s.id === scope);
  if (!scopeData) {
    throw new Error(
      `ToolReference: no scope '${scope}' in tools.json (have: ${data.scopes
        .map((s) => s.id)
        .join(', ')})`,
    );
  }

  const groups: ToolGroup[] = useMemo(() => {
    const selected = group
      ? scopeData.groups.filter((g) => g.id === group)
      : scopeData.groups;
    const needle = filter.trim().toLowerCase();
    if (!needle) return selected;
    return selected
      .map((g) => ({
        ...g,
        tools: g.tools.filter(
          (t) =>
            t.name.toLowerCase().includes(needle) ||
            t.description.toLowerCase().includes(needle),
        ),
      }))
      .filter((g) => g.tools.length > 0);
  }, [scopeData, group, filter]);

  const shown = groups.reduce((n, g) => n + g.tools.length, 0);
  const total = (group ? scopeData.groups.filter((g) => g.id === group) : scopeData.groups).reduce(
    (n, g) => n + g.tools.length,
    0,
  );

  return (
    <div>
      <div className={styles.controls}>
        <input
          type="search"
          className={styles.filter}
          placeholder={`Filter ${total} tools by name or description…`}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label="Filter tools"
        />
        {filter && (
          <span className={styles.count}>
            {shown} of {total}
          </span>
        )}
      </div>

      {groups.length === 0 && <p>No tools match “{filter}”.</p>}

      {groups.map((g) => (
        <section key={g.id}>
          {!group && <Heading as="h2">{g.label}</Heading>}
          {g.tools.map((tool) => (
            <ToolEntry key={tool.name} tool={tool} />
          ))}
        </section>
      ))}
    </div>
  );
}

function ToolEntry({tool}: {tool: ToolDoc}): React.JSX.Element {
  const {readOnlyHint, destructiveHint} = tool.annotations;
  const props = Object.entries(tool.inputSchema.properties ?? {});
  const required = new Set(tool.inputSchema.required ?? []);

  return (
    <div className={styles.tool}>
      {/* The id makes /modules/mcp/tools/gateway#browse_tags a stable deep link. */}
      <Heading as="h3" id={tool.name} className={styles.name}>
        <code>{tool.name}</code>
      </Heading>

      <div className={styles.badges}>
        {readOnlyHint ? (
          <span className={`${styles.badge} ${styles.readOnly}`}>read-only</span>
        ) : (
          <span className={`${styles.badge} ${styles.write}`}>write</span>
        )}
        {destructiveHint && (
          <span className={`${styles.badge} ${styles.destructive}`}>destructive</span>
        )}
      </div>

      <p className={styles.description}>{tool.description}</p>

      {props.length > 0 ? (
        <table className={styles.args}>
          <thead>
            <tr>
              <th>Argument</th>
              <th>Type</th>
              <th>Description</th>
            </tr>
          </thead>
          <tbody>
            {props.map(([argName, spec]) => (
              <tr key={argName}>
                <td>
                  <code>{argName}</code>
                  {required.has(argName) && <span className={styles.required}> required</span>}
                </td>
                <td>
                  <code>{spec.type ?? 'any'}</code>
                </td>
                <td>
                  {spec.description}
                  <ArgExtras spec={spec} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className={styles.noArgs}>No arguments.</p>
      )}
    </div>
  );
}

function ArgExtras({spec}: {spec: JsonSchemaProperty}): React.JSX.Element | null {
  const hasDefault = spec.default !== undefined;
  const hasEnum = Array.isArray(spec.enum) && spec.enum.length > 0;
  if (!hasDefault && !hasEnum) return null;

  return (
    <div className={styles.extras}>
      {hasEnum && (
        <span>
          One of:{' '}
          {spec.enum!.map((v, i) => (
            <React.Fragment key={v}>
              {i > 0 && ', '}
              <code>{v}</code>
            </React.Fragment>
          ))}
          .{' '}
        </span>
      )}
      {hasDefault && (
        <span>
          Defaults to <code>{JSON.stringify(spec.default)}</code>.
        </span>
      )}
    </div>
  );
}
