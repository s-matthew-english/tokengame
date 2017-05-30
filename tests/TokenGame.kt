import org.ethereum.crypto.ECKey
import org.ethereum.solidity.compiler.CompilationResult
import org.ethereum.solidity.compiler.SolidityCompiler
import org.ethereum.util.blockchain.StandaloneBlockchain
import org.ethereum.util.blockchain.SolidityContract
import org.junit.Before
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TokenGame {
    companion object {
        val compiledContract by lazy {
            val compilerResult = SolidityCompiler.compile(File("contracts/tokengame.sol"), true, SolidityCompiler.Options.ABI, SolidityCompiler.Options.BIN, SolidityCompiler.Options.INTERFACE, SolidityCompiler.Options.METADATA)
            assertFalse(compilerResult.isFailed, compilerResult.errors)
            CompilationResult.parse(compilerResult.output).contracts.mapKeys { Regex("^.*:(.*)$").find(it.key)!!.groups[1]!!.value }
        }
        val token by lazy {
            compiledContract["Token"]!!
        }
        val withdraw by lazy {
            compiledContract["Withdraw"]!!
        }
        val tokenGame by lazy {
            compiledContract["TokenGame"]!!
        }
        val zeroCap by lazy {
            compiledContract["ZeroCap"]!!
        }
        val alice = ECKey()
        val bob = ECKey()
        val carol = ECKey()
        val dan = ECKey()
    }

    lateinit var blockchain: StandaloneBlockchain
    lateinit var game: SolidityContract
    lateinit var game_token: SolidityContract
    lateinit var excess_token: SolidityContract
    lateinit var excess_withdraw: SolidityContract
    val aliceAddress get() = BigInteger(1, alice.address)
    val bobAddress get() = BigInteger(1, bob.address)
    val carolAddress get() = BigInteger(1, carol.address)
    val danAddress get() = BigInteger(1, dan.address)

    @Before
    fun `setup`() {
        blockchain = StandaloneBlockchain()
                .withAutoblock(true)
                .withAccountBalance(alice.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(bob.address, BigInteger.valueOf(2).pow(128))
                .withAccountBalance(carol.address, BigInteger.ZERO)
                .withAccountBalance(dan.address, BigInteger.ONE)
        blockchain.createBlock()
        blockchain.sender = alice
        game = blockchain.submitNewContract(tokenGame, 1) // cap is 1 wei
        val game_token_addr = game.callConstFunction("game_token")[0] as ByteArray
        game_token = blockchain.createExistingContractFromABI(token.abi, game_token_addr)
        val excess_token_addr = game.callConstFunction("excess_token")[0] as ByteArray
        excess_token = blockchain.createExistingContractFromABI(token.abi, excess_token_addr)
        val excess_withdraw_addr = game.callConstFunction("excess_withdraw")[0] as ByteArray
        excess_withdraw = blockchain.createExistingContractFromABI(withdraw.abi, excess_withdraw_addr)
    }

    @Test
    fun `game token creation`() {
        blockchain.sender = bob
        val result = game.callFunction(1000000L, "play")
        assertTrue(result.isSuccessful)
        assertEquals(BigInteger("1000000"), game.callConstFunction("total_wei_given")[0] as java.math.BigInteger)
        assertEquals(BigInteger("1000000"), game_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("1000000"), excess_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger)
        assertEquals(BigInteger("1000000"), blockchain.blockchain.repository.getBalance(game.address))
    }

    @Test
    fun `finalise before end time`() {
        val result = game.callFunction("finalise")
        assertFalse(result.isSuccessful)
    }

    @Test
    fun `finalise after the end time`() {
        blockchain.sender = bob
        game.callFunction(1000000L, "play")
        val end_time = game.callConstFunction("end_time")[0] as BigInteger
        blockchain = blockchain.withCurrentTime(Date(end_time.toLong()*1000L))
        val block = blockchain.createBlock();
        assertTrue(block.header.timestamp > end_time.toLong())
        blockchain.sender = bob
        val alice_before_finalise = blockchain.blockchain.repository.getBalance(alice.address)
        val result = game.callFunction("finalise")
        assertTrue(result.isSuccessful)
        val alice_after_finalise = blockchain.blockchain.repository.getBalance(alice.address)
        assertEquals(BigInteger.ONE, alice_after_finalise - alice_before_finalise)
        assertEquals(BigInteger("999999"), blockchain.blockchain.repository.getBalance(excess_withdraw.address))
        // Bob withdraws
        blockchain.sender = bob
        val approveResult = excess_token.callFunction("approve", excess_withdraw.address, BigInteger("1000000"))
        assertTrue(approveResult.isSuccessful)
        val withdrawResult = excess_withdraw.callFunction("withdraw")
        assertTrue(withdrawResult.isSuccessful)
        assertEquals(BigInteger.ZERO, blockchain.blockchain.repository.getBalance(excess_withdraw.address))
    }

    @Test
    fun `play after the end time`() {
        blockchain.sender = bob
        game.callFunction(1000000L, "play")
        val end_time = game.callConstFunction("end_time")[0] as BigInteger
        blockchain = blockchain.withCurrentTime(Date(end_time.toLong()*1000L))
        val block = blockchain.createBlock();
        assertTrue(block.header.timestamp > end_time.toLong())
        val result = game.callFunction(1000000L, "play")
        assertFalse(result.isSuccessful)
    }

    @Test
    fun `strangers cannot mint`() {
        blockchain.sender = bob
        val mintResult1 = game_token.callFunction("mint", bob.address, BigInteger.ONE)
        assertFalse(mintResult1.isSuccessful)
        val mintResult2 = excess_token.callFunction("mint", bob.address, BigInteger.ONE)
        assertFalse(mintResult2.isSuccessful)
    }
}
