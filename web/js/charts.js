const CHART_THEME = {
  text: '#2b1c13',
  muted: '#725b46',
  grid: 'rgba(138, 90, 53, 0.12)',
  border: 'rgba(138, 90, 53, 0.18)',
  panel: 'rgba(255, 253, 248, 0.98)',
  green: '#8a5a35',
  greenDark: '#5b371f',
  greenSoft: '#ead8c4',
  red: '#a74d3d',
  amber: '#b9823f',
  aqua: '#c8a47b',
  blue: '#9b7456',
  violet: '#d7b58a'
};

function syncChartTheme() {
  const isLight = document.body?.dataset.theme === 'light';

  Object.assign(CHART_THEME, {
    text: '#2b1c13',
    muted: '#725b46',
    grid: 'rgba(138, 90, 53, 0.12)',
    border: 'rgba(138, 90, 53, 0.18)',
    panel: 'rgba(255, 253, 248, 0.98)'
  });

  Chart.defaults.color = CHART_THEME.text;
  Chart.defaults.font.family = '"Bahnschrift", "Trebuchet MS", "Segoe UI", sans-serif';
  Chart.defaults.borderColor = CHART_THEME.grid;
  Chart.defaults.animation.duration = 900;
  Chart.defaults.animation.easing = 'easeOutQuart';
  Chart.defaults.plugins.legend.labels.usePointStyle = true;
  Chart.defaults.plugins.legend.labels.pointStyle = 'circle';
  Chart.defaults.plugins.legend.labels.boxWidth = 10;
  Chart.defaults.plugins.legend.labels.color = CHART_THEME.muted;
  Chart.defaults.plugins.tooltip.backgroundColor = CHART_THEME.panel;
  Chart.defaults.plugins.tooltip.borderColor = CHART_THEME.border;
  Chart.defaults.plugins.tooltip.borderWidth = 1;
  Chart.defaults.plugins.tooltip.titleColor = CHART_THEME.text;
  Chart.defaults.plugins.tooltip.bodyColor = CHART_THEME.muted;
  Chart.defaults.plugins.tooltip.padding = 12;
  Chart.defaults.plugins.tooltip.cornerRadius = 14;
  Chart.defaults.plugins.tooltip.displayColors = true;
}

syncChartTheme();

const centerTextPlugin = {
  id: 'centerTextPlugin',
  afterDraw(chart, args, pluginOptions) {
    if (!pluginOptions || !pluginOptions.lines || !chart.chartArea) return;

    const { ctx, chartArea } = chart;
    const centerX = (chartArea.left + chartArea.right) / 2;
    const centerY = (chartArea.top + chartArea.bottom) / 2;
    const lines = pluginOptions.lines.filter(Boolean);
    if (!lines.length) return;

    ctx.save();
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';

    lines.forEach((line, index) => {
      ctx.fillStyle = line.color || CHART_THEME.text;
      ctx.font = line.font || (index === 0 ? '700 24px Bahnschrift' : '600 11px Bahnschrift');
      const offset = (index - (lines.length - 1) / 2) * 18;
      ctx.fillText(line.text, centerX, centerY + offset);
    });

    ctx.restore();
  }
};

Chart.register(centerTextPlugin);

function rgba(hex, alpha) {
  const clean = String(hex || '').replace('#', '');
  const expanded = clean.length === 3 ? clean.split('').map(ch => ch + ch).join('') : clean;
  const int = parseInt(expanded, 16);
  const r = (int >> 16) & 255;
  const g = (int >> 8) & 255;
  const b = int & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function getCanvasContext(canvasId) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return null;
  if (canvas._chart) canvas._chart.destroy();
  return canvas.getContext('2d');
}

function buildGradient(ctx, area, colors, horizontal = false) {
  const gradient = horizontal
    ? ctx.createLinearGradient(area.left, area.top, area.right, area.top)
    : ctx.createLinearGradient(area.left, area.top, area.left, area.bottom);

  colors.forEach(stop => gradient.addColorStop(stop[0], stop[1]));
  return gradient;
}

function createSharedOptions({
  max = null,
  percent = false,
  horizontal = false,
  showLegend = true,
  legendPosition = 'top',
  tooltipSuffix = '',
  tooltipFormatter = null
} = {}) {
  syncChartTheme();

  const scaleValueFormat = value => {
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return value;
    return percent ? `${numeric}%` : numeric;
  };

  const axisScale = {
    beginAtZero: true,
    grid: { color: CHART_THEME.grid, drawBorder: false },
    ticks: {
      color: CHART_THEME.muted,
      padding: 8,
      callback: scaleValueFormat
    }
  };

  if (max !== null) axisScale.max = max;

  return {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index', intersect: false },
    animation: { duration: 950, easing: 'easeOutQuart' },
    indexAxis: horizontal ? 'y' : 'x',
    plugins: {
      legend: {
        display: showLegend,
        position: legendPosition,
        align: 'start'
      },
      tooltip: {
        callbacks: {
          label(context) {
            const raw = horizontal ? context.parsed.x : context.parsed.y;
            if (tooltipFormatter) return tooltipFormatter(raw, context);
            return `${context.dataset.label || context.label}: ${raw}${tooltipSuffix}`;
          }
        }
      }
    },
    scales: {
      x: horizontal
        ? axisScale
        : {
            grid: { color: 'rgba(0,0,0,0)', drawBorder: false },
            ticks: { color: CHART_THEME.muted, padding: 8 }
          },
      y: horizontal
        ? {
            grid: { color: 'rgba(0,0,0,0)', drawBorder: false },
            ticks: { color: CHART_THEME.muted, padding: 8 }
          }
        : axisScale
    }
  };
}

function metricColor(value) {
  const numeric = Number(value) || 0;
  if (numeric >= 85) return CHART_THEME.green;
  if (numeric >= 70) return CHART_THEME.aqua;
  return CHART_THEME.red;
}

function renderLineChart(canvasId, labels, presentData, absentData, options = {}) {
  syncChartTheme();
  const ctx = getCanvasContext(canvasId);
  if (!ctx) return;

  const sharedOptions = createSharedOptions({
    max: options.max ?? null,
    percent: !!options.percent,
    tooltipSuffix: options.tooltipSuffix || ''
  });

  const chart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: options.primaryLabel || 'Present',
          data: presentData,
          borderColor: CHART_THEME.green,
          backgroundColor(context) {
            const area = context.chart.chartArea;
            if (!area) return rgba(CHART_THEME.green, 0.25);
            return buildGradient(context.chart.ctx, area, [
              [0, rgba(CHART_THEME.green, 0.34)],
              [1, rgba(CHART_THEME.green, 0.02)]
            ]);
          },
          fill: true,
          tension: 0.38,
          borderWidth: 2.5,
          pointRadius: 3,
          pointHoverRadius: 7,
          pointBackgroundColor: '#fffdf8',
          pointBorderColor: CHART_THEME.green,
          pointBorderWidth: 2
        },
        {
          label: options.secondaryLabel || 'Absent',
          data: absentData,
          borderColor: CHART_THEME.red,
          backgroundColor(context) {
            const area = context.chart.chartArea;
            if (!area) return rgba(CHART_THEME.red, 0.16);
            return buildGradient(context.chart.ctx, area, [
              [0, rgba(CHART_THEME.red, 0.2)],
              [1, rgba(CHART_THEME.red, 0.01)]
            ]);
          },
          fill: true,
          tension: 0.36,
          borderWidth: 2,
          pointRadius: 2.5,
          pointHoverRadius: 6,
          pointBackgroundColor: '#fffdf8',
          pointBorderColor: CHART_THEME.red,
          pointBorderWidth: 2
        }
      ]
    },
    options: {
      ...sharedOptions,
      plugins: {
        ...sharedOptions.plugins,
        legend: {
          ...sharedOptions.plugins.legend,
          display: true
        }
      }
    }
  });

  ctx.canvas._chart = chart;
}

function renderDonut(canvasId, labels, data, colors, options = {}) {
  syncChartTheme();
  const ctx = getCanvasContext(canvasId);
  if (!ctx) return;

  const palette = colors && colors.length
    ? colors
    : [CHART_THEME.green, CHART_THEME.red, CHART_THEME.amber, CHART_THEME.aqua];

  const chart = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels,
      datasets: [{
        data,
        backgroundColor: palette,
        borderColor: rgba('#fffdf8', 0.95),
        borderWidth: 4,
        hoverOffset: 10
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      cutout: options.cutout || '72%',
      plugins: {
        legend: {
          position: options.legendPosition || 'bottom',
          labels: {
            color: CHART_THEME.muted,
            padding: 16
          }
        },
        centerTextPlugin: {
          lines: options.centerTextLines || []
        },
        tooltip: {
          callbacks: {
            label(context) {
              const value = context.parsed || 0;
              const suffix = options.tooltipSuffix || '';
              return `${context.label}: ${value}${suffix}`;
            }
          }
        }
      }
    }
  });

  ctx.canvas._chart = chart;
}

function renderBarChart(canvasId, labels, data, datasetLabelOrOptions = 'Attendance Rate (%)', max = 100) {
  syncChartTheme();
  const ctx = getCanvasContext(canvasId);
  if (!ctx) return;

  const options = typeof datasetLabelOrOptions === 'object'
    ? datasetLabelOrOptions
    : { datasetLabel: datasetLabelOrOptions, max };

  const horizontal = !!options.horizontal;
  const percent = options.percent !== false;
  const chartMax = options.max ?? (percent ? 100 : null);
  const labelsData = data.map(value => Number(value) || 0);

  const sharedOptions = createSharedOptions({
    max: chartMax,
    percent,
    horizontal,
    showLegend: !!options.showLegend,
    legendPosition: options.legendPosition || 'top',
    tooltipSuffix: options.tooltipSuffix || (percent ? '%' : ''),
    tooltipFormatter: options.tooltipFormatter || null
  });

  if (horizontal) {
    if (options.yAxisWidth) {
      sharedOptions.scales.y.afterFit = scale => {
        scale.width = options.yAxisWidth;
      };
    }
    if (options.labelMaxLength) {
      sharedOptions.scales.y.ticks.callback = function(value) {
        const label = String(this.getLabelForValue(value) || '');
        return label.length > options.labelMaxLength
          ? label.slice(0, options.labelMaxLength - 1) + '...'
          : label;
      };
    }
    sharedOptions.scales.y.ticks.padding = options.yTickPadding ?? 4;
    sharedOptions.scales.x.ticks.padding = options.xTickPadding ?? 6;
  }

  if (options.layoutPadding) {
    sharedOptions.layout = { padding: options.layoutPadding };
  }

  const chart = new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [{
        label: options.datasetLabel || 'Series',
        data: labelsData,
        borderRadius: options.borderRadius || 14,
        borderSkipped: false,
        categoryPercentage: options.categoryPercentage ?? (horizontal ? 0.78 : 0.66),
        barPercentage: options.barPercentage ?? 0.9,
        barThickness: options.barThickness,
        maxBarThickness: options.maxBarThickness,
        backgroundColor(context) {
          const value = labelsData[context.dataIndex] || 0;
          const strong = options.colors?.[context.dataIndex] || metricColor(value);
          const soft = rgba(strong, 0.3);
          const area = context.chart.chartArea;
          if (!area) return soft;
          return buildGradient(
            context.chart.ctx,
            area,
            horizontal
              ? [[0, rgba(strong, 0.32)], [1, strong]]
              : [[0, strong], [1, rgba(strong, 0.22)]],
            horizontal
          );
        },
        hoverBackgroundColor(context) {
          const value = labelsData[context.dataIndex] || 0;
          const strong = options.colors?.[context.dataIndex] || metricColor(value);
          return rgba(strong, 0.92);
        }
      }]
    },
    options: {
      ...sharedOptions,
      plugins: {
        ...sharedOptions.plugins,
        legend: {
          ...sharedOptions.plugins.legend,
          display: !!options.showLegend
        }
      }
    }
  });

  ctx.canvas._chart = chart;
}

function renderSingleLineChart(
  canvasId,
  labels,
  data,
  datasetLabel = 'Rate (%)',
  color = CHART_THEME.aqua,
  options = {}
) {
  syncChartTheme();
  const ctx = getCanvasContext(canvasId);
  if (!ctx) return;

  const chart = new Chart(ctx, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        label: datasetLabel,
        data,
        borderColor: color,
        backgroundColor(context) {
          const area = context.chart.chartArea;
          if (!area) return rgba(color, 0.2);
          return buildGradient(context.chart.ctx, area, [
            [0, rgba(color, 0.3)],
            [1, rgba(color, 0.02)]
          ]);
        },
        fill: true,
        tension: 0.36,
        borderWidth: 2.6,
        pointRadius: 3,
        pointHoverRadius: 7,
        pointBorderColor: color,
        pointBorderWidth: 2,
        pointBackgroundColor: '#fffdf8'
      }]
    },
    options: {
      ...createSharedOptions({
        max: options.max ?? 100,
        percent: options.percent !== false,
        showLegend: options.showLegend ?? true,
        tooltipSuffix: options.tooltipSuffix || '%'
      })
    }
  });

  ctx.canvas._chart = chart;
}
