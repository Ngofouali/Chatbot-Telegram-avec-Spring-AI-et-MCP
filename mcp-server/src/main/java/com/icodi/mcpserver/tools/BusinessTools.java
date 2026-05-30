package com.icodi.mcpserver.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class BusinessTools {

    @Tool(description = "Retourne la date du jour au format ISO.")
    public String currentDate(){
        return LocalDate.now().toString();
    }

    @Tool(description = "Liste les produits disponible dans le catalogue.")
    public List<Product> listProducts(){
        return List.of(
                new Product(1L, "Ordinateur portable", 12000),
                new Product(2L, "Imprimanate laser", 25000),
                new Product(3L, "Smarthphone", 45000)
        );
    }

    @Tool(description = "Calcule le prix TTC à partir d'un montant HT et d'un taux de TVA en pourcentage.")
    public double computeTtc(
            @ToolParam(description = "Montant hors taxe") double ht,
            @ToolParam(description = "Taux de TVA en %") double tva) {
        return ht*(1 + tva /100.0);
    }

    public record Product(Long id, String name, double price){}
}
