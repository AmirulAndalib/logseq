## Repository Layout
- `src/`: Core source code
  - `src/main/`: The core logic of the application
	- `src/main/mobile/`: Mobile app code
	- `src/main/frontend/inference_worker/`: Code running in a webworker for text-embedding and vector-search
	- `src/main/frontend/worker/`: Code running in an another webworker
		- `src/main/frontend/worker/rtc/`: RTC(Real Time Collaboration) related code
	- `src/main/frontend/components/`: UI components
  - `src/electron/`: Code specifically for the Electron desktop application.
  - `src/test/`: unit-tests
- `deps/`: Internal dependencies/modules
- `clj-e2e/`: End to end test code

## Testing Commands
- Run linters and unit-tests: `bb dev:lint-and-test`
- Run single focused unit-test:
  - Add the `:focus` keyword to the test case: `(deftest ^:focus test-name ...)`
  - `bb dev:test -i focus`

## Review Checklist
- Linters and unit-tests must pass
- Check the review notes listed in `prompts/review.md`.
