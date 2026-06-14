import { BRAND_LOGOS } from '../logos.js';

// brand: null means the gym is excluded from the list
const RSG_BRAND_RULES = [
    { prefix: 'McFIT',              brand: 'McFIT' },
    { prefix: 'KLUB McFIT',         brand: 'KLUB McFIT' },
    { prefix: 'JOHN REED',          brand: 'John Reed' },
    { prefix: 'John Reed',          brand: 'John Reed' },
    { prefix: "JOHN & JANE'S",      brand: null },
    { prefix: "Gold's Gym",         brand: "Gold's Gym" },
    { prefix: 'High5 Ghost Studio', brand: null },
    { prefix: 'ALDI SPORTS',        brand: null },
    { prefix: 'HEIMAT',             brand: null },
    { prefix: 'RSG Group',          brand: null },
    { prefix: 'Zentrale',           brand: null },
  ];

  const BESTFIT_BRAND_RULES = [
    { prefix: 'Ai Fitness',    brand: 'Ai Fitness' },
    { prefix: 'FIT STAR',      brand: null },
    { prefix: 'Testing',       brand: null },
    { prefix: 'Ai Verwaltung', brand: null },
  ];
  
  function extractBrand(studioName, rules) {
    const match = rules.find(r => studioName.startsWith(r.prefix));
    return match ? match.brand : 'unknown';
  }
  
  function extractLocation(studioName, rules) {
    const match = rules.find(r => studioName.startsWith(r.prefix));
    return match ? studioName.slice(match.prefix.length).trim() : studioName;
  }

  export function transformRSG(rawData) {
    return rawData
      .map(gym => {
        const brand = extractBrand(gym.studioName, RSG_BRAND_RULES);
        return {
          gymId: gym.id,
          gymName: gym.studioName,
          location: extractLocation(gym.studioName, RSG_BRAND_RULES),
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
          location: extractLocation(gym.studioName, BESTFIT_BRAND_RULES),
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
      location: gym.attributes.title,
      city: gym.attributes.field_address.locality,
      operatorId: 'fitness-first',
      brand,
      logoUrl: BRAND_LOGOS[brand] ?? null,
    }));
  }

  export function transformFitX(rawData) {
    const brand = 'fitx';
    return rawData.map(gym => ({
      gymId: gym.id,
      gymName: gym.name,
      location: gym.name.replace(/^FitX\s+/i, ''),
      city: gym.address.city,
      operatorId: 'fitx',
      brand,
      logoUrl: BRAND_LOGOS[brand] ?? null,
    }));
  }