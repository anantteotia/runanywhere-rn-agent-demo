import React, {useEffect, useRef} from 'react';
import {ScrollView, StyleSheet, Text} from 'react-native';

interface Props {
  output: string;
}

type Segment = {text: string; bold: boolean};

type LineKind = 'day' | 'section' | 'bullet' | 'plain';

function splitBold(line: string): Segment[] {
  const segments: Segment[] = [];
  const regex = /\*\*(.*?)\*\*/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;

  while ((match = regex.exec(line)) !== null) {
    if (match.index > lastIndex) {
      segments.push({text: line.slice(lastIndex, match.index), bold: false});
    }
    segments.push({text: match[1], bold: true});
    lastIndex = match.index + match[0].length;
  }

  if (lastIndex < line.length) {
    segments.push({text: line.slice(lastIndex), bold: false});
  }

  const cleaned = segments.length > 0 ? segments : [{text: line, bold: false}];
  return cleaned.map(seg => ({
    ...seg,
    text: seg.bold ? seg.text : seg.text.replace(/\*\*/g, ''),
  }));
}

function isDayHeading(line: string): boolean {
  return /^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)$/i.test(
    line.trim(),
  );
}

function kindOf(line: string): LineKind {
  const trimmed = line.trim();
  if (isDayHeading(trimmed)) return 'day';
  if (trimmed.endsWith(':')) return 'section';
  if (trimmed.startsWith('• ')) return 'bullet';
  return 'plain';
}

function formatLines(raw: string): string[] {
  if (!raw) return [];

  const base = raw
    .split('\n')
    .map(line => {
      const trimmed = line.trim();
      if (!trimmed) return '';
      if (/^[*#@_-]{2,}$/.test(trimmed)) {
        return '';
      }
      const noMarkdown = line
        .replace(/__/g, '')
        .replace(/`/g, '')
        .replace(/\s+#\s+/g, ' ')
        .replace(/^\s*#+\s+/, '')
        .replace(/[@#]{2,}/g, '');
      if (/^[-*]\s+/.test(trimmed)) {
        const cleaned = trimmed.replace(/^[-*]\s+/, '');
        return `• ${cleaned}`;
      }
      if (/^>\s+/.test(trimmed)) {
        return trimmed.replace(/^>\s+/, '• ');
      }
      if (/^\d+\.\s+/.test(trimmed)) {
        return trimmed.replace(/^\d+\.\s+/, '• ');
      }
      if (/^#{1,6}\s+/.test(trimmed)) {
        return trimmed.replace(/^#{1,6}\s+/, '');
      }
      return noMarkdown.trim();
    })
    .filter(line => line !== '');

  const formatted: string[] = [];
  let lastKind: LineKind | null = null;

  for (const line of base) {
    const kind = kindOf(line);

    if (kind === 'day') {
      if (formatted.length > 0) {
        formatted.push('');
      }
      formatted.push(line);
      lastKind = 'day';
      continue;
    }

    if (kind === 'section') {
      if (formatted.length > 0 && lastKind !== 'day') {
        formatted.push('');
      }
      formatted.push(`  ${line}`);
      lastKind = 'section';
      continue;
    }

    if (kind === 'bullet') {
      if (lastKind === 'section') {
        formatted.push(`    ${line}`);
      } else {
        formatted.push(`  ${line}`);
      }
      lastKind = 'bullet';
      continue;
    }

    // plain
    if (lastKind === 'day') {
      formatted.push(`  ${line}`);
    } else if (lastKind === 'section') {
      formatted.push(`    ${line}`);
    } else {
      formatted.push(line);
    }
    lastKind = 'plain';
  }

  return formatted;
}

export function OutputPanel({output}: Props): React.JSX.Element {
  const scrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    if (output) {
      scrollRef.current?.scrollToEnd({animated: true});
    }
  }, [output]);

  const lines = formatLines(output);

  return (
    <ScrollView
      ref={scrollRef}
      style={styles.container}
      contentContainerStyle={styles.content}
      nestedScrollEnabled
      showsVerticalScrollIndicator>
      {lines.length > 0 ? (
        lines.map((line, idx) => (
          <Text key={idx} style={styles.text}>
            {splitBold(line).map((seg, sidx) => (
              <Text key={sidx} style={seg.bold ? styles.bold : undefined}>
                {seg.text}
              </Text>
            ))}
          </Text>
        ))
      ) : (
        <Text style={styles.text}>Agent output will appear here.</Text>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    minHeight: 220,
    maxHeight: 360,
  },
  content: {
    padding: 16,
  },
  text: {
    color: '#dbe7ff',
    fontFamily: 'monospace',
    fontSize: 13,
    lineHeight: 20,
  },
  bold: {
    fontWeight: '700',
    color: '#f4f7ff',
  },
});
