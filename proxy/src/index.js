import { OPERATORS } from './config.js';
import {
  transformFitnessFirst,
  transformFitX,
  transformRSG,
  transformBestFit
} from './transformers/gyms.js';


const TRANSFORMERS = {
  'fitnessfirst': transformFitnessFirst,
  'fitx': transformFitX,
  'rsg-group': transformRSG,
  'bestfit': transformBestFit
};

async function fetchOperatorGyms(operator) {

  const response = await fetch(operator.gymListUrl);

  console.log(`Fetching gym list from ${operator.name}...`);

  if (!response.ok) {
    console.error(`Failed to fetch gym list from ${operator.name}: ${response.status} ${response.statusText}`);
    return [];
  }
  const rawData = await response.json();
  const transformer = TRANSFORMERS[operator.id];
  const gymList = transformer(rawData);

  return gymList;
  
};

async function fetchAllGyms(env) {
  const allGyms = [];

  for (const operator of OPERATORS) {
    try {
        const gyms = await fetchOperatorGyms(operator);
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
      const operator = url.pathname.split('/')[1];
      const gymId = url.pathname.split('/')[2];

      for (const operator of OPERATORS) {
        if (operator.id === operator) {
          const operatorOccupancyUrl = operator.occupancyUrl.replace('{gymId}', gymId)
          try {
            const response = await fetch(operatorOccupancyUrl, {
              method: 'GET',
              headers: operator.headers
            });
            const data = await response.json();

            return new Response(JSON.stringify(data), {
              headers: { 'content-type': 'application/json' }
            })
              
          } catch(error) {
            console.error(error);
            return new Response('Unknown error fetching occupancy', { status: 500 })
          }
        }
      }
    }

    return new Response('Not found', { status: 404 })
  },

  async scheduled(event, env, ctx) {
    console.log('Scheduled job running at:', new Date().toISOString());

    try {
      const GymList = await fetchAllGyms(env);

      console.log('Fetched and stored ${gymList.length} gyms')

    } catch (error) {
      console.error('Scheduled job failed:', error.message);
    }
  }
}