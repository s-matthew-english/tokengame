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
    }

    @Test
    fun `game token creation`() {
        blockchain.sender = alice
        val game = blockchain.submitNewContract(tokenGame, 0)
        val excess_token_addr = game.callConstFunction("excess_token")[0] as ByteArray
        val excess_token = blockchain.createExistingContractFromABI(token.abi, excess_token_addr)
        val game_token_addr = game.callConstFunction("game_token")[0] as ByteArray
        println(game_token_addr)
        val game_token = blockchain.createExistingContractFromABI(token.abi, game_token_addr)
        blockchain.sender = bob
        val result = game.callFunction(1000000L, "play")
        assertTrue(result.isSuccessful)
        val total_wei_given = game.callConstFunction("total_wei_given")[0] as java.math.BigInteger
        assertEquals(BigInteger("1000000"), total_wei_given)
        val bob_game_tokens = game_token.callConstFunction("balanceOf", bob.address)[0] as BigInteger
        assertEquals(BigInteger("1000000"), bob_game_tokens)
    }
}
