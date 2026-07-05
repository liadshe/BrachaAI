#!/bin/bash
echo "Starting automated database seeding..."

# Loop through all JSON files in the mounted directory
for file in /init-data/*.json; do
    filename=$(basename "$file")

    # Extract the middle part between the dots (e.g., "calls")
    collection=$(echo "$filename" | cut -d'.' -f2)

    echo "Importing $filename into collection: $collection"

    mongoimport --username="$MONGO_INITDB_ROOT_USERNAME" \
                --password="$MONGO_INITDB_ROOT_PASSWORD" \
                --authenticationDatabase=admin \
                --db=brachaai \
                --collection="$collection" \
                --file="$file" \
                --jsonArray
done
