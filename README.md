# Arabic CloudStream Extensions

A collection of Arabic content providers for [CloudStream](https://github.com/recloudstream/cloudstream).

## Providers

| Provider | Status | Content Types |
|----------|--------|---------------|
| **FaselHD** | ✅ Working | Movies, TV Series, Anime, Asian Drama |
| **MyCima** | ✅ Working | Movies, TV Series |
| **CimaLeek** | ✅ Working | Movies, TV Series, Anime, Asian Drama |
| **EgyBest** | ✅ Working | Movies, TV Series |
| **EgyDead** | ✅ Working | Movies, TV Series, Anime |
| **ArabSeed** | ✅ Working | Movies, TV Series |

## Installation

1. Open CloudStream
2. Go to Settings → Extensions → Add Repository
3. Enter the repo URL: `https://raw.githubusercontent.com/YOUR_USERNAME/Abu-Repo/builds/repo.json`
4. Install the providers you want

## Building

```bash
# Build all providers
./gradlew assembleDebug

# Build specific provider
./gradlew :FaselHD:assembleDebug
```

## Deployment

After building:
1. The `.cs3` files will be in each provider's `build/outputs/` directory
2. Push to GitHub and create a `builds` branch with the `plugins.json` and `.cs3` files

## Features

- 🎬 Search across all providers
- 📺 TV Series with season/episode support
- 🎞️ Multiple video quality options
- 🔗 Multi-server fallback
- 🌐 Arabic language support

## Credits

- Built using [CloudStream Gradle Plugin](https://github.com/recloudstream/gradle)
- Uses [NiceHttp](https://github.com/Blatzar/NiceHttp) for networking
- Parsing with [Jsoup](https://jsoup.org/)
