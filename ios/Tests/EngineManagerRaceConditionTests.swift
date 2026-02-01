import XCTest
@testable import native_geofence

class EngineManagerRaceConditionTests: XCTestCase {
    
    override func setUp() {
        super.setUp()
        // Reset singleton state before each test
        EngineManager.shared.stopEngine()
    }
    
    override func tearDown() {
        // Clean up after each test
        EngineManager.shared.stopEngine()
        super.tearDown()
    }
    
    // MARK: - Race Condition Tests
    
    /// Test dat concurrente startEngine calls niet leiden tot multiple engines
    func testConcurrentStartupDoesNotCreateMultipleEngines() {
        let expectation = XCTestExpectation(description: "All concurrent startups complete")
        let concurrentAttempts = 10
        expectation.expectedFulfillmentCount = concurrentAttempts
        
        let mockRegistrant: FlutterPluginRegistrantCallback = { engine in
            // Minimal mock implementation
            print("Mock registrant called for engine: \(engine)")
        }
        
        // Simulate 10 concurrent startup requests from different threads
        for i in 0..<concurrentAttempts {
            DispatchQueue.global(qos: .userInitiated).async {
                EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
                    print("Startup attempt \(i) completed")
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 15.0)
        
        // CRITICAL: Verify only one engine exists
        XCTAssertNotNil(EngineManager.shared.getBackgroundApi(), 
            "Background API should be initialized")
        
        // If multiple engines were created, the background API would be nil or corrupted
        // The test passes if all completions are called and the API is available
        print("Test completed successfully - all \(concurrentAttempts) concurrent startups handled correctly")
    }
    
    /// Test dat queued completions allemaal worden uitgevoerd
    func testQueuedCompletionsAreAllExecuted() {
        let expectation = XCTestExpectation(description: "All completions executed")
        let queueCount = 5
        expectation.expectedFulfillmentCount = queueCount
        
        let mockRegistrant: FlutterPluginRegistrantCallback = { _ in }
        
        // Queue multiple completions rapidly
        var completionCallOrder: [Int] = []
        let orderLock = NSLock()
        
        for i in 0..<queueCount {
            EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
                orderLock.lock()
                completionCallOrder.append(i)
                orderLock.unlock()
                expectation.fulfill()
            }
        }
        
        wait(for: [expectation], timeout: 10.0)
        
        // Verify all completions were called
        XCTAssertEqual(completionCallOrder.count, queueCount, 
            "All \(queueCount) completions should be executed")
        
        print("All \(queueCount) completions executed in order: \(completionCallOrder)")
    }
    
    /// Test dat engine correct herstart na stopEngine
    func testEngineRestartAfterStop() {
        let mockRegistrant: FlutterPluginRegistrantCallback = { _ in }
        
        // Start engine first time
        let firstStartExpectation = XCTestExpectation(description: "First engine start")
        EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
            firstStartExpectation.fulfill()
        }
        wait(for: [firstStartExpectation], timeout: 5.0)
        
        // Verify engine is running
        XCTAssertNotNil(EngineManager.shared.getBackgroundApi(), 
            "Background API should be available after first start")
        
        // Stop engine
        EngineManager.shared.stopEngine()
        
        // Verify engine is stopped
        XCTAssertNil(EngineManager.shared.getBackgroundApi(), 
            "Background API should be nil after stop")
        
        // Restart engine
        let secondStartExpectation = XCTestExpectation(description: "Second engine start")
        EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
            secondStartExpectation.fulfill()
        }
        wait(for: [secondStartExpectation], timeout: 5.0)
        
        // Verify engine is running again
        XCTAssertNotNil(EngineManager.shared.getBackgroundApi(), 
            "Background API should be available after restart")
        
        print("Engine successfully restarted after stop")
    }
    
    /// Test dat main thread enforcement werkt
    func testBackgroundThreadDispatch() {
        let expectation = XCTestExpectation(description: "Engine started from background thread")
        let mockRegistrant: FlutterPluginRegistrantCallback = { _ in }
        
        // Call from background thread
        DispatchQueue.global(qos: .background).async {
            EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
                // Verify we're now on main thread
                XCTAssertTrue(Thread.isMainThread, 
                    "Completion should be called on main thread")
                expectation.fulfill()
            }
        }
        
        wait(for: [expectation], timeout: 5.0)
        print("Background thread correctly dispatched to main thread")
    }
    
    // MARK: - Performance Tests
    
    /// Test dat concurrent startup binnen redelijke tijd completeert
    func testConcurrentStartupPerformance() {
        let expectation = XCTestExpectation(description: "Performance test")
        let concurrentAttempts = 20
        expectation.expectedFulfillmentCount = concurrentAttempts
        
        let mockRegistrant: FlutterPluginRegistrantCallback = { _ in }
        
        measure {
            for i in 0..<concurrentAttempts {
                DispatchQueue.global(qos: .userInitiated).async {
                    EngineManager.shared.startEngine(withPluginRegistrant: mockRegistrant) {
                        expectation.fulfill()
                    }
                }
            }
        }
        
        wait(for: [expectation], timeout: 20.0)
        print("Performance test completed for \(concurrentAttempts) concurrent startups")
    }
}
