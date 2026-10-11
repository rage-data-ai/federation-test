db = db.getSiblingDB('profile');
db.users.insertMany([
  { custkey: 1, segment: 'gold', city: 'NYC' },
  { custkey: 2, segment: 'silver', city: 'Shanghai' },
  { custkey: 3, segment: 'gold', city: 'Berlin' }
]);
