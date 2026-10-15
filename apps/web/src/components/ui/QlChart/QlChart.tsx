import {
  AreaChart, Area, BarChart, Bar, LineChart, Line,
  XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import styles from './QlChart.module.css';

type ChartVariant = 'area' | 'bar' | 'line' | 'spark';

interface ChartDataPoint { label?: string; value: number; [key: string]: unknown; }

interface QlChartProps {
  variant?: ChartVariant;
  data: ChartDataPoint[];
  color?: string;
  height?: number;
  showAxes?: boolean;
  showGrid?: boolean;
  /** Formats the y axis ticks and tooltip values. */
  valueFormat?: (n: number) => string;
}

const TOOLTIP_STYLE = {
  background: '#fffdf7', border: '1px solid rgba(27,26,23,0.10)',
  borderRadius: 8, fontSize: 12, fontFamily: 'Inter',
};

const AXIS_TICK = { fontSize: 11, fill: '#6b6558', fontFamily: 'Inter' };
const Y_TICK    = { fontSize: 11, fill: '#6b6558', fontFamily: 'JetBrains Mono, monospace' };

export function QlChart({ variant = 'area', data, color = '#4a6b4f', height = 200, showAxes = true, showGrid = false, valueFormat }: QlChartProps) {
  if (variant === 'spark') {
    return (
      <div data-testid="ql-chart-spark" className={styles.spark} style={{ height: 56 }}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 3, right: 0, bottom: 3, left: 0 }}>
            <defs>
              <linearGradient id="sparkFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity={0.15} />
                <stop offset="100%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <Area type="monotone" dataKey="value" stroke={color} strokeWidth={1.5} fill="url(#sparkFill)" dot={false} isAnimationActive={false} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    );
  }

  return (
    <div data-testid="ql-chart" className={styles.chart} style={{ height }}>
      <div data-testid={`ql-chart-${variant}`} style={{ width: '100%', height: '100%' }}>
        <ResponsiveContainer width="100%" height="100%">
          {variant === 'bar' ? (
            <BarChart data={data} margin={{ top: 4, right: 4, bottom: 4, left: 4 }}>
              {showGrid && <CartesianGrid stroke="rgba(27,26,23,0.10)" vertical={false} />}
              {showAxes && <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} />}
              {showAxes && <YAxis tick={Y_TICK} axisLine={false} tickLine={false} width={valueFormat ? 56 : 40} tickFormatter={valueFormat} />}
              <Tooltip contentStyle={TOOLTIP_STYLE} formatter={valueFormat ? (v) => valueFormat(Number(v)) : undefined} />
              <Bar dataKey="value" fill={color} radius={[3, 3, 0, 0]} />
            </BarChart>
          ) : variant === 'line' ? (
            <LineChart data={data} margin={{ top: 4, right: 4, bottom: 4, left: 4 }}>
              {showGrid && <CartesianGrid stroke="rgba(27,26,23,0.10)" vertical={false} />}
              {showAxes && <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} />}
              {showAxes && <YAxis tick={Y_TICK} axisLine={false} tickLine={false} width={valueFormat ? 56 : 40} tickFormatter={valueFormat} />}
              <Tooltip contentStyle={TOOLTIP_STYLE} formatter={valueFormat ? (v) => valueFormat(Number(v)) : undefined} />
              <Line type="monotone" dataKey="value" stroke={color} strokeWidth={2} dot={false} />
            </LineChart>
          ) : (
            <AreaChart data={data} margin={{ top: 4, right: 4, bottom: 4, left: 4 }}>
              <defs>
                <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={color} stopOpacity={0.12} />
                  <stop offset="100%" stopColor={color} stopOpacity={0} />
                </linearGradient>
              </defs>
              {showGrid && <CartesianGrid stroke="rgba(27,26,23,0.10)" vertical={false} />}
              {showAxes && <XAxis dataKey="label" tick={AXIS_TICK} axisLine={false} tickLine={false} />}
              {showAxes && <YAxis tick={Y_TICK} axisLine={false} tickLine={false} width={valueFormat ? 56 : 40} tickFormatter={valueFormat} />}
              <Tooltip contentStyle={TOOLTIP_STYLE} formatter={valueFormat ? (v) => valueFormat(Number(v)) : undefined} />
              <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2} fill="url(#areaFill)" dot={false} />
            </AreaChart>
          )}
        </ResponsiveContainer>
      </div>
    </div>
  );
}
