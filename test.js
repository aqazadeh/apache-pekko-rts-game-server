import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.2.0/index.js';

export const options = {
    stages: [
        { duration: '20s', target: 200 },
        { duration: '20s', target: 500 },
        { duration: '20s', target: 1000 },
        { duration: '10s', target: 2000 },
        { duration: '10s', target: 0 },
    ],
};

const CLUSTER_NODES = [
    'http://localhost:8080',
    'http://localhost:8081',
    'http://localhost:8082',
];

const BUILDING_TYPES = ['CASTLE', 'BARRACKS', 'FARM', 'MINE'];

export default function () {
    const node = CLUSTER_NODES[Math.floor(Math.random() * CLUSTER_NODES.length)];
    const playerId = `Player-${randomIntBetween(1, 10000)}`;
    const buildingId = `b-${randomIntBetween(1, 100)}`;
    const type = BUILDING_TYPES[Math.floor(Math.random() * BUILDING_TYPES.length)];

    const url = `${node}/api/player/${playerId}`;

    const payload = JSON.stringify({
        buildingId: buildingId,
        type: type,
    });

    // ÇÖZÜM BURADA: params içine 'tags' ekledik
    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
        tags: { name: 'PlayerUpgrade' }, // k6 tüm bu farklı URL'leri 'PlayerUpgrade' adı altında toplar
    };

    const res = http.post(url, payload, params);

    check(res, {
        'status is 202': (r) => r.status === 202,
        'transaction time < 500ms': (r) => r.timings.duration < 500,
    });

    sleep(0.1);
}