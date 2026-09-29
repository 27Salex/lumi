# Third-party notices

Lumi's own code is licensed under the [Apache License 2.0](LICENSE). It uses, bundles or downloads the following
third-party components and services, each under its own terms.

## Bundled in the APK

| Component | Use | License |
|---|---|---|
| [openWakeWord](https://github.com/dscripka/openWakeWord) feature models (`assets/oww/melspectrogram.tflite`, `assets/oww/embedding_model.tflite`) | "Oye Lumi" audio features | Apache License 2.0 (the embedding model derives from Google's `speech_embedding`, Apache 2.0) |
| `assets/oww/oye_lumi.bin` | "Oye Lumi" classifier | Trained for this project; Apache License 2.0 |
| [Inter](https://rsms.me/inter/) font | UI typeface | SIL Open Font License 1.1 |
| [LiteRT](https://github.com/google-ai-edge/LiteRT) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | On-device inference | Apache License 2.0 |
| [Vosk Android](https://github.com/alphacep/vosk-api) | Voice print for "Train my voice" | Apache License 2.0 |
| AndroidX, Jetpack Compose, Room, Glance, Kotlin, kotlinx.coroutines, kotlinx.serialization | App framework | Apache License 2.0 |
| Google Play services (Location, Auth) and ML Kit GenAI Prompt API | Geofences, Google sign-in, Gemini Nano | [Google APIs Terms of Service](https://developers.google.com/terms) / ML Kit terms |

## Downloaded at runtime (only if you choose to)

| Component | Use | Terms |
|---|---|---|
| [Gemma 4 E2B](https://ai.google.dev/gemma) (`gemma-4-E2B-it.litertlm`) | On-device language model | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) and [Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy) |
| [Vosk small Spanish model](https://alphacephei.com/vosk/models) (`vosk-model-small-es-0.42`) | Voice print for "Train my voice" | Apache License 2.0 |

## Online services (used only for the related feature)

| Service | Use | Terms / attribution |
|---|---|---|
| [Open-Meteo](https://open-meteo.com/) | Weather forecasts | Data under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); the free API is for non-commercial use |
| [Photon](https://photon.komoot.io/) by komoot | Place search | Data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), [ODbL](https://opendatacommons.org/licenses/odbl/) |
| [Gemini API](https://ai.google.dev/) | Optional cloud model with your own key | [Gemini API Terms](https://ai.google.dev/gemini-api/terms) |
| Google Tasks API | Optional two-way sync | [Google APIs Terms of Service](https://developers.google.com/terms) |

"Gemini", "Gemma", "Google Calendar", "Google Tasks", "WhatsApp" and "Samsung Galaxy" are trademarks of their owners.
Lumi is an independent project and is not affiliated with or endorsed by them.
