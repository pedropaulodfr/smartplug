'use strict';

/**
 * Normaliza os DPs crus (venham da LAN ou da nuvem) para unidades humanas.
 *
 * A escala oficial do produto, quando conhecida, tem prioridade; sem ela,
 * aplicamos heuristicas para os casos mais comuns.
 */

function toNumber(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Number(value))) {
    return Number(value);
  }
  return null;
}

function normalize(rawDps, config) {
  const map = config.dps || {};
  const scaleOf = (key) => {
    const value = config.scaling?.[key];
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
  };

  const read = (key) => (key === undefined ? null : toNumber(rawDps[String(key)]));

  let on = rawDps[String(map.switch)];
  if (typeof on === 'string') on = on === 'true' || on === 'on' || on === '1';

  let power = read(map.power);
  let voltage = read(map.voltage);
  let current = read(map.current);
  let energy = read(map.energy);

  // Sem escala conhecida, aplica heuristicas (as unidades variam por firmware).
  if (voltage !== null && scaleOf('voltage') === null && voltage > 1000) voltage /= 10;
  if (current !== null && scaleOf('current') === null && current > 60) current /= 1000;

  if (power !== null && scaleOf('power') === null && power > 0 && voltage > 0 && current > 0) {
    const expected = voltage * current;
    const tolerance = Math.max(expected * 0.2, 5);
    if (Math.abs(power - expected) > tolerance && Math.abs(power / 10 - expected) <= tolerance) {
      power /= 10;
    }
  }

  if (energy !== null && scaleOf('energy') === null && energy > 0) energy /= 100;

  const applyScale = (value, key) => {
    if (value === null) return null;
    const scale = scaleOf(key);
    return scale === null ? value : value * scale;
  };

  return {
    on: typeof on === 'boolean' ? on : null,
    power: applyScale(power, 'power'),
    voltage: applyScale(voltage, 'voltage'),
    current: applyScale(current, 'current'),
    energy: applyScale(energy, 'energy'),
  };
}

module.exports = { normalize, toNumber };
