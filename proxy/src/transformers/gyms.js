export function transformRSG(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.studioName,
        city: gym.address.city,
        operator: 'rsg-group'

    }))
}

export function transformFitnessFirst(rawData){
    return rawData.data.map(gym => ({
        gymId: gym.attributes.drupal_internal__nid,
        gymName: gym.attributes.title,
        city: gym.attributes.field_address.locality,
        operator: 'fitnessfirst'
    }))
}

export function transformFitX(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.name,
        city: gym.address.city,
        operator: 'fitx'
    }))
}

export function transformBestFit(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.studioName,
        city: gym.address.city,
        operator: 'bestfit'
    }))
}