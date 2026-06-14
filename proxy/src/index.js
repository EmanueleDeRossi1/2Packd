import { OPERATORS } from './config.js';
import {
  transformFitnessFirst,
  transformFitX,
  transformRSG,
  transformBestFit
} from './transformers/gyms.js';

import {
  transformFitnessFirstOccupancy,
  transformFitXOccupancy,
  transformRSGOccupancy,
  transformBestFitOccupancy
} from './transformers/occupancy.js';

const LIST_TRANSFORMERS = {
  'fitness-first': transformFitnessFirst,
  'fitx': transformFitX,
  'rsg-group': transformRSG,
  'bestfit': transformBestFit
};


const OCCUPANCY_TRANSFORMERS = {
  'fitness-first': transformFitnessFirstOccupancy,
  'fitx': transformFitXOccupancy,
  'rsg-group': transformRSGOccupancy,
  'bestfit': transformBestFitOccupancy
};

async function fetchOperatorGyms(operator) {

  try {
    const response = await fetch(operator.gymListUrl, {
      headers: operator.gymListHeaders
    });

    if (!response.ok) {
      console.error(`Failed to fetch gym list from ${operator.name}: ${response.status} ${response.statusText}`);
      return [];
    }

  const rawData = await response.json();
  const transformer = LIST_TRANSFORMERS[operator.id];
  const gymList = transformer(rawData);

  return gymList;
  
} catch (error) {
  console.error(`Error in fetchOperatorGyms for ${operator.name}:`, error);
  return [];
  }
}

// Fitness first has a pagination limit
// we have to iterate through all pages to fetch all gyms
async function fetchFitnessFirstGyms(operator) {

  const allFitnessFirstRawData = [];
  let url = operator.gymListUrl;

  // collect all data
  while (url) {
    try {
      const response = await fetch(url, {
        headers: operator.gymListHeaders
      });

      if (!response.ok) {
        console.error(`Failed to fetch gym list from ${operator.name}: ${response.status} ${response.statusText}`);
        return [];
      }

      const pageData = await response.json();
      url = pageData.links?.next?.href || null;
  
      allFitnessFirstRawData.push(...pageData.data);
  
      } catch (error) {
          console.error(`Error fetching gyms for operator ${operator.name}:`, error);
          return [];
      }
    }

  const transformer = LIST_TRANSFORMERS[operator.id];
  const gymList = transformer(allFitnessFirstRawData);

  return gymList;

}


async function fetchAllGyms(env) {
  const allGyms = [];

  for (const operator of OPERATORS) {
    try {
        let gyms;

        if (operator.id === 'fitness-first') {
          gyms = await fetchFitnessFirstGyms(operator);
        } else {
          gyms = await fetchOperatorGyms(operator);
        }

      allGyms.push(...gyms);
      } catch (error) {
        console.error(`Error fetching gyms for operator ${operator.name}:`, error);
      }
  }

  const dataWithTimestamp = {
    gyms: allGyms,
    lastUpdated: new Date().toISOString(),
    totalCount: allGyms.length
  };

  await env.GYM_DATA.put('gym-list', JSON.stringify(dataWithTimestamp));

  return allGyms;
}

async function recordOccupancyHistory(env, gyms) {
  const today = new Date();
  const day = today.toISOString().split('T')[0];

  let successCount = 0;
  let errorCount = 0;

  const BATCH_SIZE = 10;
  for (let i = 0; i < gyms.length; i += BATCH_SIZE) {
    const batch = gyms.slice(i, i + BATCH_SIZE);
    await Promise.all(batch.map(async gym => {
      const operator = OPERATORS.find(o => o.id === gym.operatorId);
      if (!operator) return;

      try {
        const url = operator.occupancyUrl.replace('{gymId}', gym.gymId);
        const response = await fetch(url, { headers: operator.occupancyHeaders });
        if (!response.ok) { errorCount++; return; }

        const rawData = await response.json();
        const transformer = OCCUPANCY_TRANSFORMERS[gym.operatorId];
        const slots = transformer(rawData);

        const stmt = env.gym_occupancy_history.prepare(
          `INSERT OR REPLACE INTO occupancy_history (gym_id, location, operator_id, day, hour, occupancy)
           VALUES (?, ?, ?, ?, ?, ?)`
        );
        await env.gym_occupancy_history.batch(
          slots.map(slot => stmt.bind(gym.gymId, gym.location, gym.operatorId, day, slot.startTime, slot.occupancy))
        );
        successCount++;
      } catch (error) {
        errorCount++;
      }
    }));
  }
}

const OPERATOR_BY_CRON_MINUTE = {
  4:  'fitness-first',  // 22:04 UTC = 23:04 CET
  24: 'fitx',           // 22:24 UTC = 23:24 CET
  44: 'rsg-group',      // 22:44 UTC = 23:44 CET
  // 23:04 UTC = 00:04 CET (bestfit) handled by hour check below
};

async function runScheduledJob(env, scheduledTime, operatorOverride) {
  const date = scheduledTime ? new Date(scheduledTime) : new Date();
  const hour = date.getUTCHours();
  const minute = date.getUTCMinutes();

  let operatorId = operatorOverride;
  if (!operatorId) {
    if (hour === 23 && minute === 4) {
      operatorId = 'bestfit';
    } else {
      operatorId = OPERATOR_BY_CRON_MINUTE[minute];
    }
  }

  if (hour === 22 && minute === 4) {
    const gymList = await fetchAllGyms(env);
    console.log(`Fetched and stored ${gymList.length} gyms`);
  }

  const gymData = await env.GYM_DATA.get('gym-list');
  if (!gymData) return;
  const { gyms } = JSON.parse(gymData);
  const filtered = operatorId ? gyms.filter(g => g.operatorId === operatorId) : gyms;

  console.log(`Recording occupancy for ${filtered.length} ${operatorId} gyms`);
  await recordOccupancyHistory(env, filtered);
}


export default {

  async fetch(request, env, ctx) {

    const url = new URL(request.url);

    if (url.pathname === "/gyms") {
      const gymData = await env.GYM_DATA.get('gym-list')

      if (!gymData) {
        return new Response('No gym data available', { status: 404 });
      }

      return new Response(gymData, {
        headers: {
           'content-type': 'application/json',
           'cache-control': 'public, max-age=3600' // Cache for 1 hour
          }
      });
    }

    else if (url.pathname.endsWith('/occupancy')) {
      const operatorId = url.pathname.split('/')[1];
      const gymId = url.pathname.split('/')[2];

      for (const operator of OPERATORS) {
        if (operator.id === operatorId) {
          const operatorOccupancyUrl = operator.occupancyUrl.replace('{gymId}', gymId)
          try {
            const response = await fetch(operatorOccupancyUrl, {
              method: 'GET',
              headers: operator.occupancyHeaders
            });
            const rawData = await response.json();

            const transformer = OCCUPANCY_TRANSFORMERS[operatorId];
            const transformedData = transformer(rawData);
    
            return new Response(JSON.stringify(transformedData), {
                  headers: { 'content-type': 'application/json' }
            })
              
          } catch(error) {
            console.error(error);
            return new Response('Unknown error fetching occupancy', { status: 500 })
          }
        }
      }
    }

    if (url.pathname === '/trigger') {
      const secret = request.headers.get('X-Trigger-Secret');
      if (!env.TRIGGER_SECRET || secret !== env.TRIGGER_SECRET) {
        return new Response('Unauthorized', { status: 401 });
      }
      const operatorId = url.searchParams.get('operator');
      if (url.searchParams.get('refresh-gyms') === 'true') {
        ctx.waitUntil(fetchAllGyms(env));
      } else {
        ctx.waitUntil(runScheduledJob(env, null, operatorId));
      }
      return new Response('OK');
    }

    return new Response('Not found', { status: 404 })
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(runScheduledJob(env, event.scheduledTime));
  }
}