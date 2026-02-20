export const OPERATORS = [
    {
      id: 'fitnessfirst',
      name: 'Fitness First',
      gymListUrl: 'https://www.fitnessfirst.de/api/v1/node/club_page',
      occupancyUrl: 'https://www.fitnessfirst.de/club/api/checkins/{gymId}',
      gymListHeaders: {},
      occupancyHeaders: {},
      timeout: 10000
    },
    {
      id: 'fitx',
      name: 'FitX',
      gymListUrl: 'https://mein.fitx.de/nox/public/v1/studios',
      occupancyUrl: 'https://mein.fitx.de/nox/public/v1/studios/{gymId}/utilization',
      gymListHeaders: {
        'x-tenant': 'fitx'
      },
      occupancyHeaders: {
        'x-tenant': 'fitx'
      },
      timeout: 10000
    },
    {
      id: 'rsg-group',
      name: 'RSG Group',
      gymListUrl: 'https://rsg-group.api.magicline.com/connect/v1/studio',
      occupancyUrl: 'https://my.mcfit.com/nox/public/v1/studios/{gymId}/utilization/v2/today',
      occupancyHeaders: {
        'x-tenant': 'rsg-group',
        'Accept': 'application/json'
      },
      timeout: 10000
    },
    {
      id: 'bestfit',
      name: 'BestFit',
      gymListUrl: 'https://bestfit.api.magicline.com/connect/v1/studio',
      occupancyUrl: 'https://bestfit.api.magicline.com/connect/v1/studio/{gymId}/utilization',
      gymListHeaders: {},
      occupancyHeaders: {},
      timeout: 10000
    }
  ];