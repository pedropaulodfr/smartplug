'use strict';

/**
 * Traduz a especificacao do produto (API da Tuya) para o mapa de DPs usado
 * pelo controle local. Cada fabricante escolhe IDs proprios: este mesmo
 * modelo usa 20/21/22/23 em vez dos tradicionais 17/18/19/20.
 */

const CODE_TO_FIELD = {
  switch: 'switch',
  switch_1: 'switch',
  cur_power: 'power',
  cur_voltage: 'voltage',
  cur_current: 'current',
  add_ele: 'energy',
};

function parseValues(values) {
  if (!values) return {};
  if (typeof values === 'object') return values;
  try {
    return JSON.parse(values);
  } catch {
    return {};
  }
}

/**
 * @param {object} spec resposta de /v1.1/devices/{id}/specifications
 * @returns {{dps: object, scaling: object, labels: object}}
 */
function mapFromSpecifications(spec) {
  const dps = {};
  const scaling = {};
  const labels = {};
  const codes = {};

  const items = [...(spec?.status || []), ...(spec?.functions || [])];

  for (const item of items) {
    const field = CODE_TO_FIELD[item.code];
    if (!field || dps[field] !== undefined) continue;

    dps[field] = Number(item.dp_id);
    codes[field] = item.code;

    const values = parseValues(item.values);
    if (typeof values.scale === 'number' && values.scale !== 0) {
      scaling[field] = 10 ** -values.scale;
    }
    if (values.unit) labels[field] = values.unit;
  }

  return { dps, scaling, labels, codes };
}

module.exports = { mapFromSpecifications };
