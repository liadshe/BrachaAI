# start project 
```bash
cd frontend
npm install
npm run dev
```

browser url: `http://localhost:5173`


## Project Commands

```bash
# Start watch mode
npm run dev

# Build for production
npm run build
npm run preview
npm run lint
```


# to check on phone 
F12
Ctrl + Shift + IiPhone SE
iPhone 12 Pro
Pixel 7
Galaxy S20

# to test on android studio emulator - you need to build the project and copy it to /android/app/src/main/assets/www
cd frontend
npm run build 
rm -rf ../android/app/src/main/assets/www/*
cp -R dist/* ../android/app/src/main/assets/www