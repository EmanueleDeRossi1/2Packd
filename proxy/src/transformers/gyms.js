export function transformRSG(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.studioName,
        city: gym.address.city,
        operatorId: 'rsg-group'

    }))
}

export function transformFitnessFirst(rawData){
    return rawData.map(gym => ({
        gymId: gym.attributes.field_magicline_studio_id,
        gymName: `Fitness First ${gym.attributes.title}`,
        city: gym.attributes.field_address.locality,
        operatorId: 'fitnessfirst'
    }))
}

export function transformFitX(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.name,
        city: gym.address.city,
        operatorId: 'fitx'
    }))
}

export function transformBestFit(rawData){
    return rawData.map(gym => ({
        gymId: gym.id,
        gymName: gym.studioName,
        city: gym.address.city,
        operatorId: 'bestfit'
    }))
}