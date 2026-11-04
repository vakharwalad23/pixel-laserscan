APK := android/app/build/outputs/apk/release/app-release.apk
APP_ID := dev.vakharwalad23.laserscan
RUN := docker compose run --rm -e CI=1 dev

.PHONY: image install shell typecheck lint test check prebuild apk run logs clean distclean

image:
	docker compose build dev

install: image
	docker compose run --rm -e NPM_CI=1 dev true

shell: image
	docker compose run --rm dev

typecheck: image
	$(RUN) npx tsc --noEmit

lint: image
	$(RUN) npx expo lint

test: image
	$(RUN) npm run test:core

check: typecheck lint test

prebuild: image
	$(RUN) npx expo prebuild -p android

apk: image
	$(RUN) npm run build:apk

run:
	adb install -r $(APK)
	adb shell am start -n $(APP_ID)/.MainActivity

logs:
	adb logcat -s LaserScan LaserScanGL ReactNativeJS

clean:
	rm -rf android .expo modules/laser-core/android/build modules/laser-core/android/.cxx
	rm -f modules/laser-core/cpp-tests/t modules/laser-core/cpp-tests/*.ply modules/laser-core/cpp-tests/*.obj

distclean: clean
	docker compose down -v --rmi all
	rm -rf node_modules
