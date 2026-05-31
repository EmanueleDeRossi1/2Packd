import { BRAND_LOGOS } from '../logos.js';

const RSG_BRAND_RULES = [
    { prefix: 'McFIT',              brand: 'mcfit' },
    { prefix: 'KLUB McFIT',         brand: 'klub-mcfit' },
    { prefix: 'JOHN REED',          brand: 'john-reed' },
    { prefix: 'John Reed',          brand: 'john-reed' },
    { prefix: "JOHN & JANE'S",      brand: 'john-jane' },
    { prefix: "Gold's Gym",         brand: 'golds-gym' },
    { prefix: 'High5 Ghost Studio', brand: 'high5' },
    { prefix: 'McFIT Ghost Studio', brand: 'mcfit' },
    { prefix: 'ALDI SPORTS',        brand: 'aldi-sports' },
    { prefix: 'HEIMAT',             brand: 'heimat' },
    { prefix: 'RSG Group',          brand: null },   // exclude
    { prefix: 'Zentrale',           brand: null },   // exclude
  ];
  
  const BESTFIT_BRAND_RULES = [
    { prefix: 'Ai Fitness',    brand: 'ai-fitness' },
    { prefix: 'FIT STAR',      brand: 'fit-star' },
    { prefix: 'Testing',       brand: null },        // exclude
    { prefix: 'Ai Verwaltung', brand: null },        // exclude
  ];
  
  function extractBrand(studioName, rules) {
    const match = rules.find(r => studioName.startsWith(r.prefix));
    return match ? match.brand : 'unknown';
  }
  
  export function transformRSG(rawData) {
    return rawData
      .map(gym => {
        const brand = extractBrand(gym.studioName, RSG_BRAND_RULES);
        return {
          gymId: gym.id,
          gymName: gym.studioName,
          city: gym.address.city,
          operatorId: 'rsg-group',
          brand,
          logoUrl: BRAND_LOGOS[brand] ?? null,
        };
      })
      .filter(gym => gym.brand !== null);
  }

  export function transformBestFit(rawData) {
    return rawData
      .map(gym => {
        const brand = extractBrand(gym.studioName, BESTFIT_BRAND_RULES);
        return {
          gymId: gym.id,
          gymName: gym.studioName,
          city: gym.address.city,
          operatorId: 'bestfit',
          brand,
          logoUrl: BRAND_LOGOS[brand] ?? null,
        };
      })
      .filter(gym => gym.brand !== null);
  }

  export function transformFitnessFirst(rawData) {
    const brand = 'fitness-first';
    return rawData.map(gym => ({
      gymId: gym.attributes.field_magicline_studio_id,
      gymName: `Fitness First ${gym.attributes.title}`,
      city: gym.attributes.field_address.locality,
      operatorId: 'fitnessfirst',
      brand,
      logoUrl: BRAND_LOGOS[brand] ?? null,
    }));
  }

  export function transformFitX(rawData) {
    const brand = 'fitx';
    return rawData.map(gym => ({
      gymId: gym.id,
      gymName: gym.name,
      city: gym.address.city,
      operatorId: 'fitx',
      brand,
      logoUrl: BRAND_LOGOS[brand] ?? null,
    }));
  }